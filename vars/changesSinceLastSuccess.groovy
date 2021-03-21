#!/usr/bin/env groovy
def call() {
  def changes = ""
  build = currentBuild
  while(build != null && build.result != 'SUCCESS') {
    changes += "In ${build.id}:\n"
      for (changeLog in build.changeSets) {
        for(entry in changeLog.items) {
          changes += "* ${entry.msg} by ${entry.author} \n"
        }
      }
    build = build.previousBuild
  }
  return changes
}
