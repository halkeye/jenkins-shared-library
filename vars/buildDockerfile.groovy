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
      stage("Build") {
        environment { DOCKER = credentials("${credential}") }
        steps {
          sh "docker login --username=\"$DOCKER_USR\" --password=\"$DOCKER_PSW\" ${registry}"
          sh "docker pull ${registry}${imageName} || true"
          sh "docker build -t ${registry}${imageName} ."
        }
      }
      stage("Deploy master as latest") {
        when { branch "master" }
        environment { DOCKER = credentials("${credential}") }
        steps {
          sh "docker login --username=\"$DOCKER_USR\" --password=\"$DOCKER_PSW\" ${registry}"
          sh "docker tag ${registry}${imageName} ${registry}${imageName}:master"
          sh "docker tag ${registry}${imageName} ${registry}${imageName}:${GIT_COMMIT}"
          sh "docker push ${registry}${imageName}:master"
          sh "docker push ${registry}${imageName}:${GIT_COMMIT}"
          sh "docker push ${registry}${imageName}"
          script {
            if (currentBuild.description) {
              currentBuild.description = currentBuild.description + " / "
            }
            currentBuild.description = "master / ${GIT_COMMIT}"
          }
        }
      }
      stage("Deploy tag as tag") {
        when { buildingTag() }
        environment { DOCKER = credentials("${credential}") }
        steps {
          sh "docker login --username=\"$DOCKER_USR\" --password=\"$DOCKER_PSW\" ${registry}"
          sh "docker tag ${registry}${imageName} ${registry}${imageName}:${TAG_NAME}"
          sh "docker push ${registry}${imageName}:${TAG_NAME}"
          script {
            if (currentBuild.description) {
              currentBuild.description = currentBuild.description + " / "
            }
            currentBuild.description = "${TAG_NAME}"
          }
        }
      }
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
