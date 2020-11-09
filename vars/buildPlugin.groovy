#!/usr/bin/env groovy
def call(Map params = [:]) {
  pipeline {
    agent { docker 'maven' }
    environment {
      HOME = "${env.WORKSPACE}"
    }
    stages {
      stage('build') {
        steps {
          sh ('mvn package')
          archiveArtifacts artifacts: '**/target/*.hpi,**/target/*.jpi,**/target/*.jar', fingerprint: true
          junit('**/target/surefire-reports/**/*.xml,**/target/failsafe-reports/**/*.xml,**/target/invoker-reports/**/*.xml')
        }
      }
    }
  }
}
