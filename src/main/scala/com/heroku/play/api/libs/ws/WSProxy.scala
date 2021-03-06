package com.heroku.play.api.libs.ws

import play.api.libs.iteratee.Enumerator
import play.api.libs.concurrent.Promise
import com.ning.http.client._
import com.ning.http.client.AsyncHandler.STATE
import play.api.mvc.{Results, Result, ResponseHeader, SimpleResult}
import play.api.libs.ws.WS


object WSProxy {


  def proxyGetAsync(url: String, responseHeadersToOverwrite: (String, String)*) = proxyRequestAsync(WS.client.prepareGet(url).build(), responseHeadersToOverwrite.toMap)

  def proxyGetAsyncAuthenticated(url: String, authHeaderValue: String, responseHeadersToOverwrite: (String, String)*) = proxyRequestAsync(WS.client.prepareGet(url).addHeader("AUTHORIZATION", authHeaderValue).build(), responseHeadersToOverwrite.toMap)

  def proxyRequestAsync(req: Request, responseHeadersToOverwrite: Map[String, String] = Map.empty): Promise[Result] = {
    val enum = Enumerator.imperative[Array[Byte]]()
    val headers = Promise[HttpResponseHeaders]()
    val status = Promise[Int]()


    WS.client.executeRequest(req, new AsyncHandler[Unit] {
      def onThrowable(p1: Throwable) {
        enum.close()
      }

      def onBodyPartReceived(part: HttpResponseBodyPart): STATE = {
        while (!enum.push(part.getBodyPartBytes)) {
          Thread.sleep(10)
        }
        STATE.CONTINUE
      }

      def onStatusReceived(s: HttpResponseStatus): STATE = {
        status.redeem(s.getStatusCode)
        STATE.CONTINUE
      }

      def onHeadersReceived(h: HttpResponseHeaders): STATE = {
        headers.redeem(h)
        if (h.getHeaders.containsKey("Content-Length") && h.getHeaders.get("Content-Length").get(0) != "0") {
          STATE.CONTINUE
        } else {
          STATE.ABORT
        }
      }

      def onCompleted() {
      }
    })


    import collection.JavaConverters._

    status.flatMap {
      s => headers.map {
        h =>
          val hmap = h.getHeaders.iterator().asScala.map {
            entry => entry.getKey -> entry.getValue.get(0)
          }.toMap ++ responseHeadersToOverwrite
          if (h.getHeaders.containsKey("Content-Length") && h.getHeaders.get("Content-Length").get(0) != "0") {
            SimpleResult(ResponseHeader(s, hmap), enum)
          } else {
            SimpleResult(ResponseHeader(s, hmap), Enumerator(Results.EmptyContent()))
          }
      }
    }

  }


}
