def call(imageName, registry = "", credential = "dockerhub-halkeye") {
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
}
