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
        cleanWs()

        env.HOME = "${WORKSPACE}"
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
          docker.image('jnorwood/helm-docs:v1.5.0').inside('--entrypoint ""') {
            dir(name) {
              sh 'helm-docs'
            }
          }
          docker.image('alpine/helm:3.3.4').inside('--entrypoint ""') {
            sh "helm lint ${name}"
            sh "helm package ${name}"
          }
          archiveArtifacts("${name}*.tgz")
        }

        if (env.BRANCH_NAME == "master") {
          stage('Tag') {
            withCredentials([usernamePassword(credentialsId: 'github-halkeye', passwordVariable: 'github_psw', usernameVariable: 'github_usr')]) {
              dir(name) {
                sh 'git config --global user.email "jenkins@gavinmogan.com"'
                sh 'git config --global user.name "Jenkins"'
                sh 'git config --global push.default simple'
                sh "git tag -a -m 'v${version}' v${version}"

                newUrl = env.GIT_URL.replace("https://", "https://${github_usr}:${github_psw}@");
                sh "git remote add tags ${newUrl}"
                sh "git push tags v${version}"
                sh "git remote remove tags"
              }
            }
          }
        }

        lock('helm-charts') {
          stage('Checkout halkeye/helm-charts') {
            withCredentials([usernamePassword(credentialsId: 'github-halkeye', passwordVariable: 'github_psw', usernameVariable: 'github_usr')]) {
              sh 'git clone -b gh-pages https://${github_usr}:${github_psw}@github.com/halkeye/helm-charts.git helm-charts'
            }
          }
          stage('Fix timestamps') {
            dir('helm-charts') {
              // Blatently taken from https://stackoverflow.com/a/55609950
              sh '''
                git ls-tree -r --name-only HEAD | while read filename; do
                  unixtime=$(git log -1 --format="%at" -- "${filename}")
                  touchtime=$(date -d @$unixtime +'%Y%m%d%H%M.%S')
                  touch -t ${touchtime} "${filename}"
                done
              '''
            }
          }

          stage('Build Index') {
            docker.image('alpine/helm:3.3.4').inside('--entrypoint ""') {
              dir('helm-charts') {
                sh """
                  mkdir -p ${name}
                  mv ../${name}*.tgz ${name}
                  helm repo index ./
                  mkdir /tmp/helm-repo-html
                  wget -O - https://github.com/halkeye/helm-repo-html/releases/download/v0.0.8/helm-repo-html_0.0.8_linux_x86_64.tar.gz | tar xzf - -C /tmp/helm-repo-html
                  /tmp/helm-repo-html/bin/helm-repo-html
                  """
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
        } catch (getEmailsErr) {
        }
        emailext(
          attachLog: true,
          recipientProviders: [developers()],
          body: "Build failed (see ${env.BUILD_URL}): ${err}",
          subject: "[JENKINS] ${env.JOB_NAME} failed",
          to: emails
        )
        throw err
      }
    }
  }
}
