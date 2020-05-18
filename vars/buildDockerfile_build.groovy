def call(imageName, registry = "", credential = "dockerhub-halkeye") {
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
          --build-arg "GIT_COMMIT_REV=${GIT_COMMIT_REV}" \
          --build-arg "GIT_SCM_URL=${GIT_SCM_URL}" \
          --build-arg "BUILD_DATE=${BUILD_DATE}" \
          --label "org.opencontainers.image.source=${GIT_SCM_URL}" \
          --label "org.label-schema.vcs-url=${GIT_SCM_URL}" \
          --label "org.opencontainers.image.url==${SCM_URI}" \
          --label "org.label-schema.url=${SCM_URI}" \
          --label "org.opencontainers.image.revision=${GIT_COMMIT_REV}" \
          --label "org.label-schema.vcs-ref=${GIT_COMMIT_REV}" \
          --label "org.opencontainers.created=${BUILD_DATE}" \
          --label "org.label-schema.build-date=${BUILD_DATE}" \
          .
      """
    }
  }
}
