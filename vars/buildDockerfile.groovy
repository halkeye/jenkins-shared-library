import java.text.SimpleDateFormat;
import java.util.Date;

def call(String imageName, Map config=[:], Closure body={}) {
  if (!config.dockerfile) {
    config.dockerfile = "Dockerfile"
  }
  if (!config.registry) {
    config.registry = ""
  }
  if (!config.credential) {
    config.credential = "dockerhub-halkeye"
  }
  if (!config.mainBranch) {
    config.mainBranch = "master"
  }

  pipeline {
    agent any

    environment {
      DOCKER = credentials("dockerhub-halkeye")
      BUILD_DATE = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX").format(new Date())
      DOCKER_REGISTRY = "${config.registry}"
      IMAGE_NAME = "${config.registry}${imageName}"
      DOCKERFILE = "${config.dockerfile}"
      DATED_GIT_HASH = "${new SimpleDateFormat("yyMMddHHmmss").format(new Date())}${GIT_COMMIT.take(6)}"
      SKIP_PULL = "${config.skipPull ? "true" : "false"}"
      NO_CACHE = "${config.noCache ? "--no-cache" : ""}"
    }

    options {
      disableConcurrentBuilds()
      buildDiscarder(logRotator(numToKeepStr: '5', artifactNumToKeepStr: '5'))
      timeout(time: 60, unit: "MINUTES")
      ansiColor("xterm")
    }

    stages {
      stage("Lint") {
        steps {
          script {
            docker.image('hadolint/hadolint:latest-alpine').inside {
              sh('/bin/hadolint --no-fail --format json ${DOCKERFILE} | tee hadolint.json')
            }
            recordIssues(tools: [hadoLint(pattern: 'hadolint.json')])
          }
        }
      }
      stage("Build") {
        environment {
          SCM_URI = "${env.GIT_URL.replace('git@github.com:', 'https://github.com')}"
        }
        steps {
          script {
            sh('''
              docker version
              docker login --username="$DOCKER_USR" --password="$DOCKER_PSW" $DOCKER_REGISTRY
              [ "$SKIP_PULL" != "true" ] && (docker pull ${IMAGE_NAME} || true)
              docker build ${NO_CACHE} \
                  -t ${IMAGE_NAME} \
                  --build-arg "GIT_COMMIT_REV=$GIT_COMMIT" \
                  --build-arg "GIT_SCM_URL=$GIT_URL" \
                  --build-arg "BUILD_DATE=$BUILD_DATE" \
                  --label "org.opencontainers.image.source=$GIT_SCM_URL" \
                  --label "org.label-schema.vcs-url=$GIT_SCM_URL" \
                  --label "org.opencontainers.image.url=$SCM_URI" \
                  --label "org.label-schema.url=$SCM_URI" \
                  --label "org.opencontainers.image.revision=$GIT_COMMIT_REV" \
                  --label "org.label-schema.vcs-ref=$GIT_COMMIT_REV" \
                  --label "org.opencontainers.image.created=$BUILD_DATE" \
                  --label "org.label-schema.build-date=$BUILD_DATE" \
                  -f ${DOCKERFILE} \
                  .
            ''')
          }
        }
      }
      stage("Deploy master as latest") {
        when { branch "${config.mainBranch}" }
        environment { DOCKER = credentials("dockerhub-halkeye") }
        steps {
          script {
            sh('''
              docker login --username="$DOCKER_USR" --password="$DOCKER_PSW" $DOCKER_REGISTRY
              docker tag $IMAGE_NAME $IMAGE_NAME:${DATED_GIT_HASH}
              docker push $IMAGE_NAME:${DATED_GIT_HASH}
              docker push $IMAGE_NAME
            ''')
            if (currentBuild.description) {
              currentBuild.description = currentBuild.description + " / "
            }
            currentBuild.description = "${config.mainBranch} / ${DATED_GIT_HASH}"
          }
        }
      }
      stage("Deploy tag as tag") {
        when { buildingTag() }
        environment { DOCKER = credentials("dockerhub-halkeye") }
        steps {
          script {
            sh('''
              docker login --username="$DOCKER_USR" --password="$DOCKER_PSW" $DOCKER_REGISTRY
              docker tag $IMAGE_NAME $IMAGE_NAME:${TAG_NAME}
              docker push $IMAGE_NAME:${TAG_NAME}
            ''')
            if (currentBuild.description) {
              currentBuild.description = currentBuild.description + " / "
            }
            currentBuild.description = "${TAG_NAME}"
          }
        }
      }
      stage("Extra Steps") {
        steps {
          script {
            if (body) {
              body()
            }
          }
        }
      }
    }
    // TODO: maybe only email out when we are building master or at least not PRs
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
