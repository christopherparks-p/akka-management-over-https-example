/*
 * Copyright (C) 2017-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package example

import akka.actor.ActorSystem
import akka.cluster.Cluster
import akka.http.scaladsl.{ConnectionContext, Http, HttpsConnectionContext}
import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import akka.http.scaladsl.server.{Directives, Route}
import akka.management.scaladsl.{AkkaManagement, ManagementRouteProvider, ManagementRouteProviderSettings}
import com.typesafe.config.ConfigFactory

import java.io.InputStream
import java.security.{KeyStore, SecureRandom}
import javax.net.ssl.{KeyManagerFactory, SSLContext, TrustManagerFactory}
import scala.concurrent.Await
import scala.concurrent.duration.*
import scala.io.StdIn

class HttpManagementEndpointSpecRoutesScaladsl extends ManagementRouteProvider with Directives {
  override def routes(settings: ManagementRouteProviderSettings): Route =
    path("scaladsl") {
      get {
        complete("hello Scala")
      }
    }
}


object TestHttps extends App {
  val config = ConfigFactory.parseString(
    """
      |akka.actor.provider = cluster
      |akka.remote.log-remote-lifecycle-events = off
      |akka.remote.netty.tcp.port = 0
      |akka.remote.artery.canonical.port = 0
      |akka.loglevel = DEBUG
""".stripMargin
  )

  val httpPort = "8558"
  val configClusterHttpManager = ConfigFactory.parseString(
    s"""
akka.management.http.hostname = "127.0.0.1"
akka.management.http.port = $httpPort

"""
  )

  implicit val system: ActorSystem = ActorSystem("test", config.withFallback(configClusterHttpManager).resolve())
  implicit val ec: scala.concurrent.ExecutionContext = system.dispatcher
  try {
    //val cluster = Cluster(system)

    val password
    : Array[Char] = "password".toCharArray // do not store passwords in code, read them from somewhere safe!

    val ks: KeyStore = KeyStore.getInstance("PKCS12")

    import java.io.File
    import java.io.FileInputStream

    val file = new File("src/main/resources/keystore.p12")
    val keystore: InputStream = new FileInputStream(file)

    require(keystore != null, "Keystore required!")
    ks.load(keystore, password)

    val keyManagerFactory: KeyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm)
    keyManagerFactory.init(ks, password)

    val tmf: TrustManagerFactory = TrustManagerFactory.getInstance("SunX509")
    tmf.init(ks)

    val sslContext: SSLContext = SSLContext.getInstance("TLS")
    sslContext.init(keyManagerFactory.getKeyManagers, tmf.getTrustManagers, new SecureRandom)

    val httpsClient: HttpsConnectionContext = ConnectionContext.httpsClient((host, port) => {
      val engine = sslContext.createSSLEngine(host, port)
      engine.setUseClientMode(true)

      engine.setSSLParameters({
        val params = engine.getSSLParameters
        params.setEndpointIdentificationAlgorithm(null)
        params
      })
      engine
    })

    val management = AkkaManagement(system)
    val httpsServer: HttpsConnectionContext = ConnectionContext.httpsServer(sslContext)
    val started = management.start(_.withHttpsConnectionContext(httpsServer))

    val endpoint = s"https://127.0.0.1:$httpPort/cluster/members/"
    val httpRequest = HttpRequest(uri = endpoint)
    val responseGetMembersFuture = Http().singleRequest(httpRequest, connectionContext = httpsClient)
    val responseGetMembers: HttpResponse = Await.result(responseGetMembersFuture, 15.seconds)

    println("is akka-management working with https?")
    println(responseGetMembers.status)
    println(responseGetMembers.entity.toStrict(timeout = Duration(1, "seconds")).map(_.data.utf8String))
    

    println(s"Server now online. Please navigate to $endpoint\nPress RETURN to stop...")
    StdIn.readLine() // let it run until user presses return
    started
      //.flatMap(_.unbind()) // trigger unbinding from the port
      .onComplete(_ => system.terminate())

  }
  catch {
    case ex =>
      println("error")
      println(ex.toString)
      system.terminate()
  }
}
