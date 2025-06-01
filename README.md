# Pipeline CPS ORAS Plugin

[![Build Status](https://ci.jenkins.io/buildStatus/icon?job=Plugins/pipeline-cps-oras-plugin/main)](https://ci.jenkins.io/job/plugins/job/pipeline-cps-oras-plugin/)
[![Jenkins Plugin](https://img.shields.io/jenkins/plugin/v/pipeline-cps-oras.svg)](https://plugins.jenkins.io/pipeline-cps-oras/)
[![GitHub release](https://img.shields.io/github/release/jenkinsci/pipeline-cps-oras-plugin.svg?label=changelog)](https://github.com/jenkinsci/pipeline-cps-oras-plugin/releases/latest)
[![Contributors](https://img.shields.io/github/contributors/jenkinsci/pipeline-cps-oras-plugin.svg)](https://github.com/jenkinsci/pipeline-cps-oras-plugin/graphs/contributors)

## Introduction

This plugin allow to fetch pipeline scripts (CPS) stored into a OCI compliant registry (ORAS).

> [!WARNING]
> The ORAS Java SDK is currently in **alpha** state and might impact the stability of this plugin.
>
> It's configuration and APIs might change in future releases

<p align="left">
<a href="https://oras.land/"><img src="https://oras.land/img/oras.svg" alt="banner" width="200px"></a>
</p>


## Getting started

When configuring a pipeline job, just select the "Pipeline script from ORAS" option in the "Definition" section.

Credentials are optional if using an unsecured registry, otherwise you need to provide a username/password credential.

![config.png](docs/config.png)

In order to consume a pipeline script artifact it need to have the following media type: `application/vnd.jenkins.pipeline.manifest.v1+json`

You can push such an artifact using the [ORAS CLI](https://oras.land/docs/commands/oras_push):

```bash
oras push localhost:5000/hello:latest --artifact-type application/vnd.jenkins.pipeline.manifest.v1+json Jenkinsfile
```

You will see then on the logs the digest of the pipeline script artifact

![log.png](docs/log.png)

## LICENSE

Licensed under MIT, see [LICENSE](LICENSE.md)

