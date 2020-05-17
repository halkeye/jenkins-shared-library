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
          script {
            GIT_COMMIT_REV = sh(returnStdout: true, script: "git log -n 1 --pretty=format:'%h'").trim()
            GIT_SCM_URL = sh(returnStdout: true, script: "git remote show origin | grep 'Fetch URL' | awk '{print \$3}'").trim()
            SCM_URI = GIT_SCM_URL.replace("git@github.com:", "https://github.com/");
            BUILD_DATE = sh(returnStdout: true, script: "TZ=UTC date --rfc-3339=seconds | sed 's/ /T/'").trim()
          }
          sh """
            docker build \
              -t ${registry}${imageName} \
              -l "org.opencontainers.image.source=${GIT_SCM_URL}" \
              -l "org.label-schema.vcs-url=${GIT_SCM_URL}" \
              -l "org.opencontainers.image.url==${SCM_URI}" \
              -l "org.label-schema.url=${SCM_URI}" \
              -l "org.opencontainers.image.revision=${GIT_COMMIT_REV}" \
              -l "org.label-schema.vcs-ref=${GIT_COMMIT_REV}" \
              -l "org.opencontainers.created=${BUILD_DATE}" \
              -l "org.label-schema.build-date=${BUILD_DATE}" \
              .
          """
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
