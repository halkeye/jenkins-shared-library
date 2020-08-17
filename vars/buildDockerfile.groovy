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


  pipeline {
    agent any

    options {
      disableConcurrentBuilds()
      buildDiscarder(logRotator(numToKeepStr: '5', artifactNumToKeepStr: '5'))
      timeout(time: 60, unit: "MINUTES")
      ansiColor("xterm")
    }

    stages {
      stage("Lint") {
        agent {
          docker { image "hadolint/hadolint" }
        }
        steps {
          script {
            writeFile(file: 'hadolint.json', text: sh(returnStdout: true, script: "/bin/hadolint --format json ${config.dockerfile} || true").trim())
            recordIssues(tools: [hadoLint(pattern: 'hadolint.json')])
          }
        }
      }
      stage("Build") {
        environment { DOCKER = credentials("${config.credential}") }
        steps {
          sh "docker login --username=\"$DOCKER_USR\" --password=\"$DOCKER_PSW\" ${config.registry}"
          sh "docker pull ${config.registry}${imageName} || true"
          sh """
              export GIT_COMMIT_REV=\$(git log -n 1 --pretty=format:'%h')
              export GIT_SCM_URL=\$(git remote show origin | grep 'Fetch URL' | awk '{print \$3}')
              export SCM_URI=\$(echo \$GIT_SCM_URL | awk '{print gensub("git@github.com:","https://github.com/",\$3)}')

              docker build \
                  -t ${config.registry}${imageName} \
                  --build-arg "GIT_COMMIT_REV=\$GIT_COMMIT_REV" \
                  --build-arg "GIT_SCM_URL=\$GIT_SCM_URL" \
                  --build-arg "BUILD_DATE=\$BUILD_DATE" \
                  --label "org.opencontainers.image.source=\$GIT_SCM_URL" \
                  --label "org.label-schema.vcs-url=\$GIT_SCM_URL" \
                  --label "org.opencontainers.image.url=\$SCM_URI" \
                  --label "org.label-schema.url=\$SCM_URI" \
                  --label "org.opencontainers.image.revision=\$GIT_COMMIT_REV" \
                  --label "org.label-schema.vcs-ref=\$GIT_COMMIT_REV" \
                  --label "org.opencontainers.image.created=\$BUILD_DATE" \
                  --label "org.label-schema.build-date=\$BUILD_DATE" \
                  -f ${config.dockerfile} \
                  .
          """
        }
      }
      stage("Deploy master as latest") {
        when { branch "master" }
        environment { DOCKER = credentials("${config.credential}") }
        steps {
          sh "docker login --username=\"$DOCKER_USR\" --password=\"$DOCKER_PSW\" ${config.registry}"
          sh "docker tag ${config.registry}${imageName} ${config.registry}${imageName}:master"
          sh "docker tag ${config.registry}${imageName} ${config.registry}${imageName}:${GIT_COMMIT}"
          sh "docker push ${config.registry}${imageName}:master"
          sh "docker push ${config.registry}${imageName}:${GIT_COMMIT}"
          sh "docker push ${config.registry}${imageName}"
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
        environment { DOCKER = credentials("${config.credential}") }
        steps {
          sh "docker login --username=\"$DOCKER_USR\" --password=\"$DOCKER_PSW\" ${config.registry}"
          sh "docker tag ${config.registry}${imageName} ${config.registry}${imageName}:${TAG_NAME}"
          sh "docker push ${config.registry}${imageName}:${TAG_NAME}"
          script {
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
