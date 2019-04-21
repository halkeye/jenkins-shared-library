def call(body) {
  // evaluate the body block, and collect configuration into the object
  def pipelineParams= [:]
  if (body) {
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = pipelineParams
    body()
  }

  ansiColor('xterm') {
    timeout(10) {
      try {
        dir('chart') {
          def scmVars = checkout scm
          env.GIT_COMMIT = scmVars.GIT_COMMIT
          env.SHORT_COMMIT = env.GIT_COMMIT.take(6)
          env.GIT_BRANCH = scmVars.GIT_BRANCH
          env.BRANCH_NAME = scmVars.GIT_BRANCH
          env.GIT_PREVIOUS_COMMIT = scmVars.GIT_PREVIOUS_COMMIT
          env.GIT_URL = scmVars.GIT_URL
        }
        name = readYaml(file: './chart/Chart.yaml').get('name')
        version = readYaml(file: './chart/Chart.yaml').get('version')
        sh("mv chart ${name}")

        stage('Build') {
          docker.image('dtzar/helm-kubectl').inside {
            sh "helm init -c"
            sh "helm lint ${name}"
            sh "helm package ${name}"
          }
        }

        if (env.BRANCH_NAME == "master") {
          stage('Tag') {
            withCredentials([usernamePassword(credentialsId: 'github-halkeye', passwordVariable: 'github_psw', usernameVariable: 'github_usr')]) {
              dir('helm-charts') {
                sh 'git config --global user.email "jenkins@gavinmogan.com"'
                sh 'git config --global user.name "Jenkins"'
                sh 'git config --global push.default simple'
                sh "git tag -a -m "v${version}" v${version}"
                sh "git push --tags"
              }
            }
          }
        }

        lock('helm-charts') {
          stage('Checkout halkeye/helm-charts') {
            withCredentials([usernamePassword(credentialsId: 'github-halkeye', passwordVariable: 'github_psw', usernameVariable: 'github_usr')]) {
              sh 'git clone -b gh-pages https://${github_usr}:${github_psw}@github.com/halkeye/helm-charts.git helm-charts'
              docker.image('dtzar/helm-kubectl').inside {
                dir('helm-charts') {
                  sh """
                  mkdir -p ${name}
                  mv ../${name}*.tgz ${name}
                  mkdir -p "\$(helm home)/plugins"
                  helm plugin install https://github.com/halkeye/helm-repo-html
                  helm repo index ./
                  helm repo-html
                  """
                }
              }
            }
          }
          stage('Commit') {
            dir('helm-charts') {
              sh 'git config --global user.email "jenkins@gavinmogan.com"'
              sh 'git config --global user.name "Jenkins"'
              sh "git add index.yaml index.html ${name}"
              sh 'git commit -m "Adding package"'
            }
          }
          if (env.BRANCH_NAME == "master") {
            stage('Deploy') {
              withCredentials([usernamePassword(credentialsId: 'github-halkeye', passwordVariable: 'github_psw', usernameVariable: 'github_usr')]) {
                dir('helm-charts') {
                  sh 'git config --global user.email "jenkins@gavinmogan.com"'
                  sh 'git config --global user.name "Jenkins"'
                  sh 'git config --global push.default simple'
                  sh 'git push origin gh-pages'
                }
              }
            }
          }
        }
      } catch (err) {
        emails = "helm@gavinmogan.com"
        try {
          // TODO map and join
          email  = readYaml(file: './chart/Chart.yaml').get('maintainers')[0].get('email')
        } catch (error) {
        }
        emailext(
          attachLog: true,
          recipientProviders: [developers()],
          body: "Build failed (see ${env.BUILD_URL}): ${error}",
          subject: "[JENKINS] ${env.JOB_NAME} failed",
          to: emails
        )
        throw error
      }
    }
  }
}
