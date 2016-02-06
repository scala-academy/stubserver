package castalia.matcher

import akka.actor.{ActorLogging, Actor, ActorRef, Props}
import akka.http.scaladsl.model.HttpRequest
import akka.util.Timeout
import castalia.matcher.MatchResultGatherer.MatchNotFound
import castalia.matcher.MatcherActor.RespondIfMatched
import castalia.matcher.types.{Params, Segments}
import scala.util.{Success, Failure}
import scala.annotation.tailrec
import akka.pattern.{ask, pipe}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

/**
  * Created by m06f791 on 4-2-2016.
  */
object MatcherActor {
  case class RespondIfMatched(parsedUri: ParsedUri, httpRequest: HttpRequest, gatherer: ActorRef)
  def props(segments: Segments, handler: ActorRef): Props = Props(new MatcherActor(segments, handler))
}
class MatcherActor(segments: Segments, handler: ActorRef) extends Actor with ActorLogging {
  implicit val timeout = Timeout(2.seconds)

  def receive: Receive = {
    case RespondIfMatched(parsedUri, httpRequest, gatherer) =>
      matchPath(parsedUri.pathList) match {
        case Some(params) =>
          log.debug(s"MatcherActor found match: $params from $sender. Forwarding request to $handler")
          val requestMatch = new RequestMatch(httpRequest, params, parsedUri.queryParams)
          log.debug(s"($handler ? $requestMatch) pipeTo $gatherer")
          (handler ? requestMatch) pipeTo gatherer
        case None => gatherer ! MatchNotFound
      }
  }

  /**
    * Compare the segments, matching the literals and collecting the parameters on the fly
    *
    * @param requestSegments containing the path segments from the request
    */
  def matchPath(requestSegments: Segments): Option[Params] = {
    @tailrec
    def marp(requestSeg: Segments, matchSeg: Segments, params: Params): Option[Params] =
      (requestSeg, matchSeg) match {
        case (Nil, Nil) => Some(params)
        case (Nil, _) => None
        case (_, Nil) => None
        case (rhead :: rtail, mhead :: mtail) if isParam(mhead) => marp(rtail, mtail, (paramName(mhead), rhead) :: params)
        case (rhead :: rtail, mhead :: mtail) if rhead.equals(mhead) => marp(rtail, mtail, params)
        case (_, _) => None
      }

    marp(requestSegments, segments, List[(String, String)]())
  }

  private def isParam(segment: String): Boolean = {
    segment.startsWith("{") && segment.endsWith("}") || segment.startsWith("$")
  }

  private def paramName(segment: String): String =
    (segment, segment.startsWith("{"), segment.startsWith("$")) match {
      case (seg, true, _) => seg.substring(1, segment.length - 1)
      case (seg, _, true) => seg.substring(1, segment.length)
      case (seg, _, _) => seg
    }
}
