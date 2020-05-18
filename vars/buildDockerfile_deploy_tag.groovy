def call(imageName, registry = "", credential = "dockerhub-halkeye") {
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
