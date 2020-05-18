def call(imageName, registry = "", credential = "dockerhub-halkeye") {
  pipeline {
    agent any

    options {
      disableConcurrentBuilds()
      buildDiscarder(logRotator(numToKeepStr: '5', artifactNumToKeepStr: '5'))
      timeout(time: 60, unit: "MINUTES")
      ansiColor("xterm")
    }

    stages {
      buildDockerfile_build(imageName, registry, credential)
      buildDockerfile_deploy_master(imageName, registry, credential)
      buildDockerfile_deploy_tag(imageName, registry, credential)
    }
    post {
      failure {
        emailext(
          attachLog: true,
          recipientProviders: [developers()],
          body: "Build failed (see ${env.BUILD_URL})",
          subject: "[JENKINS] ${env.JOB_NAME} failed",
        )
      }
    }
  }
}
