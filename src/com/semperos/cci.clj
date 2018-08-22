(ns com.semperos.cci
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [clojure.java.shell :as sh]
            [com.semperos.cci.cli :as cli]
            clansi
            [cheshire.core :as json]
            #_[clojure.java.io :as io]
            #_[clj-http.client :as http])
  (:gen-class))

(defn env* [] (System/getenv))

(def env (memoize env*))

(defn cci-token []
  (let [env (env)]
    (or (get env "CIRCLE_TOKEN")
        (get env "CIRCLECI_TOKEN"))))

(defn cci-project-user []
  (let [env (env)]
    (or (get env "CIRCLE_PROJECT_USER")
        (get env "CIRCLECI_PROJECT_USER"))))

(defn cci-project-org []
  (let [env (env)]
    (or (get env "CIRCLE_PROJECT_ORG")
        (get env "CIRCLECI_PROJECT_ORG"))))

(defn cci-vcs-type []
  (let [env (env)]
    (or (get env "CIRCLE_VCS_TYPE")
        (get env "CIRCLECI_VCS_TYPE"))))

(defn cci-base-url
  ([vcs-type user-or-org project]
   (format "https://circleci.com/api/v1.1/project/%s/%s/%s"
           (name vcs-type)
           user-or-org
           project))
  ([vcs-type user-or-org project build-num]
   (format "https://circleci.com/api/v1.1/project/%s/%s/%s/%s"
           (name vcs-type)
           user-or-org
           project
           build-num)))

(defn exit [status msg]
  (println msg)
  (System/exit status))

(defn sanitize-ansi [s]
  (str/replace s #"(\[\d+m)" ""))

(def ^:dynamic *color* true)
(def ^:dynamic *output-format* :table)

(defn colorize [s styles]
  (if *color*
    (apply clansi/style s styles)
    s))

(def string-styles
  {"canceled"    [:black :bright]
   "fixed"       [:green]
   "not_running" [:magenta]
   "running"     [:cyan]
   "success"     [:green]
   "failed"      [:red]
   "--project"   [:red]
   "--username"  [:red]
   "--branch"    [:yellow]})

(defn colorize-status [status]
  (let [styles (get string-styles (str/triml status) [:white])]
    (colorize status styles)))

(defn- printable-widths
  [ks rows]
  (map
   (fn [k]
     (apply max
            (count (str k))
            (map #(count (sanitize-ansi (str (get % k)))) rows)))
   ks))

(defn- fmt-row
  [ks fmts]
  (fn [leader divider trailer row]
    (str leader
         (apply str (interpose divider
                               (for [[col fmt] (map vector (map #(get row %) ks) fmts)]
                                 (colorize-status (format fmt (str col))))))
         trailer)))

(defn print-table
  "Derived from clojure.pprint/print-table, but changed to:

  * Format the tables differently
  * Ignore ANSI escape code syntax in width calculations of table columns "
  ([ks rows]
   (when (seq rows)
     (let [widths  (printable-widths ks rows)
           spacers (map #(apply str (repeat % "-")) widths)
           fmts    (map #(str "%" % "s") widths)
           fmt-row (fmt-row ks fmts)]
       (println)
       (println (fmt-row "+-" "-+-" "-+" (zipmap ks spacers)))
       (println (fmt-row "| " " | " " |" (zipmap ks ks)))
       (println (fmt-row "+-" "-+-" "-+" (zipmap ks spacers)))
       (doseq [row rows]
         (println (fmt-row "| " " | " " |" row))
         (println (fmt-row "+-" "-+-" "-+" (zipmap ks spacers)))))))
  ([rows] (print-table (keys (first rows)) rows)))

(defn print-output
  "Honor the *output-format* the user asked for."
  ([x] (print-output nil x))
  ([columns x]
   (case *output-format*
     :table (let [x (if (sequential? x)
                      x
                      [x])]
              (if columns
                (print-table columns x)
                (print-table x)))
     ;; TODO Give user ability to toggle :pretty
     ;; TODO Filter the output by desired "columns"
     :json  (println (json/generate-string x {:pretty true}))
     ;; TODO Do better than straight EDN
     :edn   (prn-str x))))

(def builds-columns
  "TODO Allow users to customize this list based on what CircleCI provides."
  [:repo :branch :committer :status :start :stop :url])

(defn builds-table [builds-response]
  (map (fn [{branch    :branch
             committer :committer_name
             repo      :reponame
             start     :start_time
             status    :status
             stop      :stop_time
             url       :build_url}]
         {:branch    branch
          :committer committer
          :repo      repo
          :start     start
          :status    status
          :stop      stop
          :url       url})
       builds-response))

(defn url-encode [x]
  (java.net.URLEncoder/encode x "UTF-8"))

(defn full-url [url query-params]
  (str url (when-not (empty? query-params)
             (str "?" (->> query-params
                           (map (fn [[k v]] (str (url-encode (name k)) "=" (url-encode (str v)))))
                           (str/join "&"))))))

(defn http-get
  [url {:keys [accept as query-params]
        :or   {accept :json
               as     :json}
        :as   opts}]
  (let [url                    (full-url url query-params)
        {:keys [exit out err]} (sh/sh "curl" "-XGET" "-H" "'Accept: application/json'" url)]
    {:status 200
     :body   (json/parse-string out true)}))

(defn cci-builds [{:keys [branch filter limit
                          project token username vcs-type]
                   :or   {limit 5}}]
  (let [base-url (cci-base-url vcs-type username project)
        url    (if branch
                 (str base-url "/tree/" branch)
                 base-url)
        params (cond-> {:circle-token token
                        :limit limit}
                 filter (assoc :filter filter))
        resp   (http-get url {:accept       :json
                              :as           :json
                              :query-params params})
        status (:status resp)]
    (case status
      200 (print-output builds-columns (builds-table (:body resp)))
      (throw (ex-info (str "Call to " url " failed with a " status "status")
                      {:response resp})))))

(defn cci-build [{:keys [build-num project token username vcs-type]}]
  (let [url    (cci-base-url vcs-type username project build-num)
        params {:circle-token token}
        resp   (http-get url {:accept       :json
                              :as           :json
                              :query-params params})
        status (:status resp)]
    (case status
      ;; TODO Handle truncation
      200 (print-output (dissoc (:body resp) :all_commit_details :circle_yml :steps :workflows))
      (throw (ex-info (str "Call to " url " failed with a " status "status")
                      {:response resp})))))

#_(defn validate-limit-range
    [limit]
    (< 0 limit 101))

(def cli-options
  [["-u" "--username USER_OR_ORG_NAME" "Optionally, set CIRCLE_PROJECT_USER or CIRCLE_PROJECT_ORG."]
   ["-p" "--project PROJECT_NAME" "Project or repository name"]
   ["-b" "--branch BRANCH_NAME" "(Optional) Branch name"]
   ["-n" "--build-num BUILD_NUMBER" "(Optional) Build number, for details on single build."]
   ["-c" "--[no-]color" "Colorize output with ANSI escape codes (defaults to true)." :default true]
   ["-f" "--output-format FORMAT" "Output format. One of: 'table', 'json', 'edn'." :default "table"]
   ["-h" "--help"]
   ["-l" "--limit LIMIT" "Number of builds to view."
    :default  5
    :parse-fn #(Integer/parseInt %)
    ;; TODO Determine why neither :validate nor these seem to work.
    ;; :validate-fn [validate-limit-range]
    ;; :validate-msg ["Must be a number between 0 and 65536"]
    ]
   ["-t" "--token API_TOKEN" "Preferably, set CIRCLE_TOKEN."]
   ["-v" "--vcs-type (github|bitbucket)" "Your project's VCS hosting provider, either 'github' or 'bitbucket'."
    :default "github"]])

(defn usage [options-summary]
  (->> ["Usage: CIRCLECI_TOKEN=<api-token> cci [options]"
        ""
        "Options:"
        options-summary]
       (str/join \newline)))

(def options-keymap
  {:required :name
   :desc     :description})

(defn rename-options
  [options-map]
  (set/rename-keys options-map options-keymap))

(defn summary-fn
  [options]
  (with-out-str
    (print-table
     [:short-opt :long-opt :name :default :description]
     (map rename-options options))))

(defn parse-args
  [args]
  (let [{:keys [options arguments summary errors]}
        (cli/parse-opts args cli-options :summary-fn summary-fn)

        {:keys [branch build-num limit output-format project
                token username vcs-type]}
        options

        output-format (keyword output-format)
        token         (or token (cci-token))
        username      (or username
                          (cci-project-user)
                          (cci-project-org))
        vcs-type      (keyword vcs-type)
        return        {:options (assoc options
                                       :output-format output-format
                                       :token token
                                       :username username
                                       :vcs-type vcs-type)}]
    (binding [*color* (:color options)]
      (cond
        (:help options)
        (assoc return
               :exit-message (usage summary)
               :ok? 1)

        (not token)
        (assoc return
               :exit-message (str "You must set a "
                                  (colorize "CIRCLECI_TOKEN environment variable" [:red])
                                  " with your API token, or pass it via --token.\n"
                                  "Go to "
                                  (colorize "https://circleci.com/account/api" [:green])
                                  " to create one.")
               :ok? 0)

        (not (#{:table :json :edn} output-format))
        (assoc return
               :exit-message "Invalid output format. Must be one of: 'table', 'json', or 'edn'."
               :ok? 0)

        (not (#{:github :bitbucket} vcs-type))
        (assoc return
               :exit-message "Invalid VCS type. Must be either 'github' or 'bitbucket'."
               :ok? 0)

        (not username)
        (assoc return
               :exit-message "You must either specify a --user or set CIRCLE_PROJECT_USER or CIRCLE_PROJECT_ORG environment variables."
               :ok? 0)

        ;; N.B. CircleCI will give _all builds from the beginning of time_ if you supply a limit of 0
        (not (< 0 limit 100))
        (assoc return
               :exit-message "Invalid limit value. The limit must be: 0 < limit < 100"
               :ok? 0)

        (and branch (not project))
        (assoc return
               :exit-message "If you provide a branch, you must provide a project."
               :ok? 0)

        ;; Only required parameter is --project
        project
        return

        :else
        (assoc return
               :exit-message (usage summary)
               :ok? 0)))))

(defn run [options]
  (if-let [build-number (:build-num options)]
    (cci-build options)
    (cci-builds options))
  (shutdown-agents))

(defn -main
  [& args]
  (let [{:keys [options exit-message ok?]} (parse-args args)]
    (binding [*color*         (:color options)
              *output-format* (:output-format options)]
      (if exit-message
        (exit ([1 0] ok?) exit-message)
        (run options)))))

(comment
  ;; clj-http not compatible with GraalVM at this time.
  (def http-get http/get)

  ;; HTTPS not supported by GraalVM at this time.
  (defn http-get
    [url {:keys [accept as query-params]
          :or   {accept :json
                 as     :json}
          :as   opts}]
    (let [raw (slurp (io/as-url (full-url url query-params)))]
      {:status 200
       :body   (json/parse-string raw true)}))
  )
