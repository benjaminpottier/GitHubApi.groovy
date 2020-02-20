#!/usr/bin/env groovy

/*
 * GitHuB API for Groovy (respects rate limits)
 *
 * Expects to find your GitHub token in ~/.github.token
 */

import groovyx.net.http.RESTClient
import org.apache.http.*
import org.apache.http.protocol.*
import groovyx.net.http.*

@Grab(group='org.codehaus.groovy.modules.http-builder', module='http-builder', version='0.7.1')

class GitHubApi {

  static String token
  def rateLimit = null

  def secondsToReset() {
    rateLimit && rateLimit.reset ?
      Math.round((rateLimit.reset.time - new Date().time)/1000) : 0
  }

  // Chain response, fetching rate limit from headers 
  def updateRateLimit(response) {
    def limit = response.getHeaders('X-RateLimit-Limit')?.value[0]
    def remaining = response.getHeaders('X-RateLimit-Remaining')?.value[0]
    def reset = response.getHeaders('X-RateLimit-Reset')?.value[0]
    if (remaining != null && reset) {
      rateLimit =
        [
        limit: limit as Integer,
        remaining: remaining as Integer,
        reset: new Date(1000*(reset as Long))
        ]
    }
    response
  }

  // Initialize a new API instance
  static final def github = new RESTClient( 'https://api.github.com' ).with {
    // Allow up to 5s for responses
    client.params.setIntParameter('http.connection.timeout', 5000)
    client.params.setIntParameter('http.socket.timeout', 5000)
    delegate
  }

  // Used to fetch next link from GitHub header responses
  def getNext(response) {
    def next = null
    for (link in response.getHeaders('Link')?.value[0]?.split(',')) {
      def matcher = link.replaceAll("\\s", "") =~ /<(.*)\?page=(\d+)>;rel="next"/
      if (matcher.matches()) {
        next = [url:matcher[0][1], page:matcher[0][2]]
      }
    }
    next
  }

  // Checks rate limit and sleeps if necessary to avoid exceeding it
  def checkRateLimit() {
    if (rateLimit && rateLimit.reset && rateLimit.remaining < 1) {
      println ""
      println "\nReached limit of $rateLimit.limit calls to GitHub. Limit resets at $rateLimit.reset"
      while (rateLimit.remaining < 10) {
        print "${secondsToReset()}".padRight(10) + "\r"
        sleep(5000)
        def resp = github.get(path: '/rate_limit', 
          requestContentType:ContentType.URLENC, headers:['User-Agent':'Groovy','Authorization':"token ${this.token}"])
        updateRateLimit(resp)
      }
      println ""
    }
  }

  // Good ol' GET request, given path and any params in "query" hash
  def get(path, query=null) {
    checkRateLimit()
    def resp = github.get(path: path, query:query,
      requestContentType:ContentType.URLENC, headers:['User-Agent':'Groovy','Authorization':"token ${this.token}"])
    updateRateLimit(resp)
    def data = resp.data
    def next = getNext(resp)
    while (next) {
      resp = github.get(path: next.url, query:[page: next.page], headers:['User-Agent':'Groovy','Authorization':"token ${this.token}"])
      data += resp.data
      next = getNext(resp)
    }
    data
  }

  // HTTP PUT of :body to :path
  def put(path, body) {
    checkRateLimit()
    def resp = github.put(path: path, body: body,
      requestContentType:ContentType.URLENC, headers:['User-Agent':'Groovy','Authorization':"token ${this.token}"])
    updateRateLimit(resp)
  }

  // HTTP PATCH of :body to :path
  def patch(path, body) {
    checkRateLimit()
    def resp = github.patch(path: path, body: body, 
      requestContentType:ContentType.URLENC, headers:['User-Agent':'Groovy','Authorization':"token ${this.token}"])
    updateRateLimit(resp)
  }

  // Does a quick check of API if you run this script directly
  public static void main(String[] args) {
    def api = new GitHubApi()
    def openmrs = api.get('/orgs/openmrs')
    assert openmrs.name == 'OpenMRS'
  }
}
