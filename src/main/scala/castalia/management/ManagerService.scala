package castalia.management

import akka.actor.{ActorRef, ActorSystem}
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.pattern.ask
import akka.util.Timeout
import castalia.model.Messages.{EndpointMetricsGet, Done}
import castalia.model.Model.{EndpointMetrics, StubConfig}

import scala.concurrent.duration._
import scala.language.postfixOps

trait ManagerService {

  protected def managerActor: ActorRef
  protected implicit val system: ActorSystem

  private implicit val timeout = Timeout(2 second)
  import system.dispatcher

  val managementRoute: Route =
    pathPrefix("castalia" / "manager" / "endpoints") {
      post {
        entity(as[StubConfig]) {
          stubConfig =>

            complete {
              (managerActor ? stubConfig)
                .mapTo[Done]
                .map(result => s"${result.endpoint}")
            }
        }
      } ~ path("metrics") {
            get {
              complete {
                (managerActor ? EndpointMetricsGet).mapTo[EndpointMetrics]
              }
            }
          }
    }

}
