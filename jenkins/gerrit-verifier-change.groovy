// Copyright (C) 2015 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

import hudson.model.*
import hudson.AbortException
import hudson.console.HyperlinkNote
import java.util.concurrent.CancellationException
import groovy.json.*
import java.text.*

verbose = true

String.metaClass.encodeURL = {
  java.net.URLEncoder.encode(delegate)
}

class Globals {
  static String gerrit = "https://gerrit-review.googlesource.com/"
  static String gerritReviewer = "GerritForge CI <gerritforge@gmail.com>"
  static long curlTimeout = 10000
  static SimpleDateFormat tsFormat = new SimpleDateFormat("YYYY-MM-dd HH:mm:ss.S Z")
  static int maxChanges = 100
  static int myAccountId = 1022687
  static int waitForResultTimeout = 10000
  static Map buildsList = [:]

  static def ciTag(String operation) {
    " \"tag\" : \"autogenerated:gerrit-ci:$operation\" "
  }

  static String addReviewerTag = ciTag("addReviewer")
  static String addVerifiedTag = ciTag("addVerified")
  static String addCommentTag = ciTag("comment")
}


def gerritPost(url, jsonPayload) {
  def error = ""
  def gerritPostUrl = Globals.gerrit + url
  def curl = ['curl',
  '-n', '-s', '-S',
  '-X', 'POST', '-H', 'Content-Type: application/json',
  '--data-binary', jsonPayload,
    gerritPostUrl ]
  if(verbose) { println "CURL/EXEC> $curl" }
  def proc = curl.execute()
  def sout = new StringBuffer(), serr = new StringBuffer()
  proc.consumeProcessOutput(sout, serr)
  proc.waitForOrKill(Globals.curlTimeout)
  def curlExit = proc.exitValue()
  if(curlExit != 0) {
    error = "$curl **FAILED** with exit code = $curlExit"
    println error
    throw new IOException(error)
  }

  if(!sout.toString().trim().isEmpty() && verbose) {
    println "CURL/OUTPUT> $sout"
  }
  if(!serr.toString().trim().isEmpty() && verbose) {
    println "CURL/ERROR> $serr"
  }

  return 0
}

def gerritReview(changeNum, sha1, verified) {
  if(verified == 0) {
    return;
  }

  def resTicks = [ 'ABORTED':'\u26aa', 'SUCCESS':'\u2705', 'FAILURE':'\u274c' ]

  def msgList = Globals.buildsList.collect { type,build ->
    [ 'type': type, 'res': build.getResult().toString(), 'url': build.getBuildUrl() + "console" ]
  } sort { a,b -> a['res'].compareTo(b['res']) }

  def msgBody = msgList.collect {
    "${resTicks[it.res]} ${it.type} : ${it.res}\n    (${it.url})"
  } .join('\n')

  def addReviewerExit = gerritPost("a/changes/" + changeNum + "/reviewers", '{ "reviewer" : "' +
                                   Globals.gerritReviewer + "\" , ${Globals.addReviewerTag} }")
  if(addReviewerExit != 0) {
    println "**** ERROR: cannot add myself as reviewer of change " + changeNum + " *****"
    return addReviewerExit
  }

  def jsonPayload = '{"labels":{"Code-Review":0,"Verified":' + verified + '},' +
                    ' "message": "' + msgBody + '", ' +
                    ' "notify" : "' + (verified < 0 ? "OWNER": "OWNER_REVIEWERS") + "\" , ${Globals.addVerifiedTag} }"
  def addVerifiedExit = gerritPost("a/changes/" + changeNum + "/revisions/" + sha1 + "/review",
                                   jsonPayload)

  if(addVerifiedExit == 0) {
    println "----------------------------------------------------------------------------"
    println "Gerrit Review: Verified=" + verified + " to change " + changeNum + "/" + sha1
    println "----------------------------------------------------------------------------"
  }
  return addVerifiedExit
}

def gerritComment(buildUrl,changeNum, sha1, msgPrefix) {
  return gerritPost("a/changes/$changeNum/revisions/$sha1/review",
                    "{ \"message\": \"$msgPrefix Gerrit-CI Flow: $buildUrl\", \"notify\" : \"NONE\", ${Globals.addCommentTag} }")
}

def waitForResult(b) {
  def res = null
  def startWait = System.currentTimeMillis()
  while(res == null && (System.currentTimeMillis() - startWait) < Globals.waitForResultTimeout) {
    res = b.getResult()
    if(res == null) {
      Thread.sleep(100) {
      }
    }
  }
  return res == null ? Result.FAILURE : res
}

def getVerified(acc, res) {
  if(res == null || res == Result.ABORTED) {
    return 0
  }

  switch(acc) {
        case 0: return 0
        case 1:
          if(res == null) {
            return 0;
          }
          switch(res) {
            case Result.SUCCESS: return +1;
            case Result.FAILURE: return -1;
            default: return 0;
          }
        case -1: return -1
  }
}

def getChangedFiles(changeNum, sha1) {
  URL filesUrl = new URL(String.format("%schanges/%s/revisions/%s/files/",
      Globals.gerrit, changeNum, sha1))
  def files = filesUrl.getText().substring(5)
  def filesJson = new JsonSlurper().parseText(files)
  filesJson.keySet().findAll { it != "/COMMIT_MSG" }
}

def buildsForMode(refspec,sha1,changeUrl,mode,tools,targetBranch,retryTimes) {
    def builds = []
    for (tool in tools) {
      def buildName = "Gerrit-verifier-$tool"
      def key = "$tool/$mode"
      builds += {
                   retry (retryTimes) {
                     Globals.buildsList.put(key,
                       build(buildName, REFSPEC: refspec, BRANCH: sha1,
                             CHANGE_URL: changeUrl, MODE: mode, TARGET_BRANCH: targetBranch))
                     println "Builds status:"
                     Globals.buildsList.each {
                       n, v -> println "  $n : ${v.getResult()}\n    (${v.getBuildUrl() + "console"})"
                     }
                   }
                }
    }
    return builds
}

def sh(cwd, command) {
    def sout = new StringBuilder(), serr = new StringBuilder()
    println "SH: $command"
    def shell = command.execute([],cwd)
    shell.consumeProcessOutput(sout, serr)
    shell.waitForOrKill(30000)
    println "OUT: $sout"
    println "ERR: $serr"
}

def buildChange(change) {
  def sha1 = change.current_revision
  def changeNum = change._number
  def revision = change.revisions.get(sha1)
  def ref = revision.ref
  def patchNum = revision._number
  def branch = change.branch
  def changeUrl = Globals.gerrit + "#/c/" + changeNum + "/" + patchNum
  def refspec = "+" + ref + ":" + ref.replaceAll('ref/', 'ref/remotes/origin/')
  def tools = []
  def modes = ["reviewdb"]
  def workspace = build.environment.get("WORKSPACE")
  println "workspace: $workspace"
  def cwd = new File("$workspace")
  println "cwd: $cwd"
  println "ref: $ref"

  sh(cwd, "git fetch origin $ref")
  sh(cwd, "git checkout FETCH_HEAD")
  sh(cwd, "git fetch origin $branch")
  sh(cwd, 'git config user.name "Jenkins Build"')
  sh(cwd, 'git config user.email "jenkins@gerritforge.com"')
  sh(cwd, 'git merge --no-commit --no-edit --no-ff FETCH_HEAD')

  if(new java.io.File("$cwd/BUCK").exists()) {
    tools += ["buck"]
  } else if(new java.io.File("$cwd/BUILD").exists()) {
    tools += ["bazel"]
  }

  println "Building Change " + changeUrl

  if(branch == "master" || branch == "stable-2.14") {
    modes += "disableChangeReviewDb"
    modes += "notedbPrimary"

    def changedFiles = getChangedFiles(changeNum, sha1)
    def polygerritFiles = changedFiles.findAll { it.startsWith("polygerrit-ui") }

    if(polygerritFiles.size() > 0) {
      if(changedFiles.size() == polygerritFiles.size()) {
        println "Only PolyGerrit UI changes detected, skipping other test modes..."
        modes = ["polygerrit"]
      } else {
        println "PolyGerrit UI changes detected, adding 'polygerrit' validation..."
        modes += "polygerrit"
      }
    }
  }

  def builds = []
  println "Running validation jobs using $tools builds for $modes ..."
  modes.collect { buildsForMode(refspec,sha1,changeUrl,it,tools,branch,1) }.each { builds += it }

  def buildsWithResults = parallelBuilds(builds)

  flaky = flakyBuilds(buildsWithResults)
  if(flaky.size > 0) {
    println "** FLAKY Builds detected: ${flaky}"

    def retryBuilds = []
    def toolsAndModes = flaky.collect { it.split("/") }

    toolsAndModes.each {
      def tool = it[0]
      def mode = it[1]
      Globals.buildsList.remove(it)
      retryBuilds += buildsForMode(refspec,sha1,changeUrl,mode,[tool],branch,3)
    }
    buildsWithResults = parallelBuilds(retryBuilds)
  }

  def res = buildsWithResults.inject(1) { acc, buildResult -> getVerified(acc, buildResult[1]) }

  gerritReview(changeNum, sha1, res)

  switch(res) {
    case 0: build.state.result = ABORTED
            break
    case 1: build.state.result = SUCCESS
            break
    case -1: build.state.result = FAILURE
             break
  }
}

def parallelBuilds(builds) {
  ignore(FAILURE) {
    parallel (builds)
  }
  def results = Globals.buildsList.values().collect { waitForResult(it) }
  def buildsWithResults = []

  Globals.buildsList.keySet().eachWithIndex {
    key,index -> buildsWithResults.add(new Tuple(key, results[index]))
  }
  return buildsWithResults
}

def flakyBuilds(buildsWithResults) {
  def flaky = buildsWithResults.findAll { it[1] == null || it[1] != SUCCESS }
  if(flaky.size == buildsWithResults.size) {
    return []
  }

  return flaky.collect { it[0] }
}

def lastBuild = build.getPreviousSuccessfulBuild()
def logOut = new ByteArrayOutputStream()
if(lastBuild != null) {
  lastBuild.getLogText().writeLogTo(0,logOut)
}

def lastLog = new String(logOut.toByteArray())
def lastBuildStartTimeMillis = lastBuild == null ?
  (System.currentTimeMillis() - 1800000) : lastBuild.getStartTimeInMillis()
def sinceMillis = lastBuildStartTimeMillis - 30000
def since = Globals.tsFormat.format(new Date(sinceMillis))

def requestedChangeId = params.get("CHANGE_ID")

def processAll = false

queryUrl = 
  new URL(Globals.gerrit + "changes/?pp=0&O=3&q=" + requestedChangeId)

def changes = queryUrl.getText().substring(5)
def jsonSlurper = new JsonSlurper()
def changesJson = jsonSlurper.parseText(changes)

def acceptedChanges = changesJson.findAll {
  change ->
  sha1 = change.current_revision
  if(sha1 == null) {
      println "[WARNING] Skipping change " + change.change_id + " because it does not have any current revision or patch-set"
      return false
  }

  def verified = change.labels.Verified
  def approved = verified.approved
  def rejected = verified.rejected 

  gerritComment(build.startJob.getBuildUrl() + "console",change._number,change.current_revision,"Verification queued on")
  return true
}

for (change in acceptedChanges) {
  buildChange(change)
}
