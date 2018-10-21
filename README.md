# CircleCI Client

A client for the [CircleCI API](https://circleci.com/docs/api/v1-reference/).

Also an example of building a CLI application
in Clojure and compiling it to a native executable using
[GraalVM](https://www.graalvm.org/).

## Installation

[Download](https://github.com/semperos/cci/releases) a recent release and put it
on your `PATH`.

## Usage

1. If you don't have one, generate a CircleCI API token [here]().
1. Set a `CIRCLE_TOKEN` environment variable with your token.
1. If you have only one GitHub or Bitbucket project, set `CIRCLE_PROJECT_USER`
   or `CIRCLE_PROJECT_ORG` so you don't have to specify a `--username` at
   the CLI.
1. If you're using Bitbucket, set `CIRCLE_VCS_TYPE` so you don't have to specify
   your project's `--vcs-type` at the CLI.

Turn off colored output by passing `--no-color`.

For full details:

```
cci -h
```

## Examples

### Latest Builds

To get the last 5 builds for your project:

```
cci -p your-project
```

To focus on one branch:

```
cci -p your-project -b your-branch
```

To pull back more:

```
cci -p your-project -l 25
```

In case you didn't read the usage section and you've not defined any environment
variables:

```
cci -t your-token    \
    -u your-username \
    -p your-project  \
    -v your-vcs-type \ # default of 'github'
    -b your-branch   \ # optional
    -l a-limit         # default of 5
```

## Ideas

- [ ] (Khiem Lam) Use relative time for builds (N minutes ago...) rather than
      absolute.

## License

Copyright © 2018 Daniel Gregoire

Distributed under the
[Mozilla Public License version 2.0](https://www.mozilla.org/en-US/MPL/2.0/).

Code copied and edited from [tools.cli](https://github.com/clojure/tools.cli)
in the `com.semperos.cci.cli` namespace maintains its own copyright and license,
which has been included in the source code.
