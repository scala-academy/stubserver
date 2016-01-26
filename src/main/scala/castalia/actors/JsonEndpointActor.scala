package castalia.actors

import akka.actor._
import akka.http.scaladsl.model.StatusCodes.{Forbidden, InternalServerError}
import castalia.matcher.RequestMatch
import castalia.matcher.types.Params
import castalia.model.Model._
import castalia.{Delay, EndpointIds}
import spray.json.JsValue

import scala.concurrent.Future
import scala.concurrent.duration._

case class DelayComplete( destination: ActorRef, message: StubResponse)

/**
  * Actor that provides answers based on the json configuration that is used to create this actor
  *
  * Created by Jean-Marc van Leerdam on 2016-01-16
  */
class JsonEndpointActor(myStubConfig: StubConfig) extends Actor
  with ActorLogging
  with Delay {

  // We are assured that we are getting the dispatcher assigned to this actor
  // in case it differs from the main dispatcher for the system.
  import context._

  implicit val scheduler = system.scheduler
  override def receive: Receive = {
    case request: RequestMatch =>
      log.debug("receive requestmatch")

      // see if there is a response available for the parameters in the request
      val responseOption = findResponse(request.pathParams)

      // save reference of the sender
      val orig = sender

      responseOption match {
        case Some(response) =>
          log.debug("found a response")
          (response.response, response.delay) match {
            case (Some(content), Some(delay)) =>
              log.debug("make a delayed response with body")
              scheduleResponse(new StubResponse(response.httpStatusCode, content.toJson.toString),
                calculateDelayTime(delay), orig)
            case (Some(content), _) =>
              log.debug("make a immediate response with body")
              sender ! new StubResponse( response.httpStatusCode, content.toJson.toString)
            case (_, Some(delay)) =>
              log.debug("make a delayed response without body")
              scheduleResponse(new StubResponse(response.httpStatusCode, ""),
                calculateDelayTime(delay), orig)
            case (_, _) =>
              log.debug("make an immediate empty response")
              sender ! new StubResponse(response.httpStatusCode, "")
          }
        case _ =>
          log.debug("found no response")
          sender ! new StubResponse( Forbidden.intValue, Forbidden.reason)
      }
    case x: Any =>
      log.debug("receive unexpected message [" + x + "]")
  }

  def findResponse( pathParams: Params): Option[ResponseConfig] = {
    def findResponseRecurse( pathParams: Params, responses: List[ResponseConfig]): Option[ResponseConfig] =
      (pathParams, responses) match {
        case (_, Nil) => None
        case (params, first :: rest) => if (paramMatch(params, first.ids)) Some(first) else findResponseRecurse( params, rest)
        case (_, _) => None
      }
    findResponseRecurse(pathParams, myStubConfig.responses)
  }

  def paramMatch( left: Params, right: EndpointIds): Boolean = {
    (left, right) match {
      case (Nil, None) => true
      case (left, None) => false
      case (Nil, Some(right)) => false
      case (left, Some(right)) => left.toMap == right
    }
  }

  def calculateDelayTime( latencyConfig: LatencyConfig): FiniteDuration = {
    log.debug("calculating delay for " + latencyConfig.duration.length + " " + latencyConfig.duration.unit)
    (latencyConfig.duration, latencyConfig.duration.isFinite()) match {
      case (duration, true) => FiniteDuration(duration.length, duration.unit)
      // no match, we encountered a 'non-finite duration' let's set it to 0.
      case (_, _) => FiniteDuration(0, MILLISECONDS)
    }
  }

  def scheduleResponse(response: StubResponse,
                       delay: FiniteDuration,
                       orig: ActorRef): Unit = {
    future(Future(response), delay)
      .onComplete(f => orig ! f.getOrElse(StubResponse(InternalServerError.intValue, "Delay went wrong")))
  }

}
