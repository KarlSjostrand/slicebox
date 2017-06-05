/*
 * Copyright 2017 Lars Edenbrandt
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package se.nimsa.sbx.app.routing

import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model.{ContentTypes, HttpEntity}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.pattern.ask
import se.nimsa.sbx.anonymization.AnonymizationProtocol._
import se.nimsa.sbx.app.SliceboxBase
import se.nimsa.sbx.dicom.DicomHierarchy.Image
import se.nimsa.sbx.metadata.MetaDataProtocol._
import se.nimsa.sbx.user.UserProtocol.ApiUser
import se.nimsa.sbx.util.SbxExtensions._

import scala.concurrent.Future

trait AnonymizationRoutes {
  this: SliceboxBase =>

  def anonymizationRoutes(apiUser: ApiUser): Route =
    path("images" / LongNumber / "anonymize") { imageId =>
      put {
        entity(as[Seq[TagValue]]) { tagValues =>
          complete {
            anonymizeOne(apiUser, imageId, tagValues).map(_.map(_.image))
          }
        }
      }
    } ~ path("images" / LongNumber / "anonymized") { imageId =>
      post {
        entity(as[Seq[TagValue]]) { tagValues =>
          onSuccess(metaDataService.ask(GetImage(imageId)).mapTo[Option[Image]]) {
            case Some(image) =>
              complete(HttpEntity(ContentTypes.`application/octet-stream`, anonymizedDicomData(image, tagValues, storage)))
            case None =>
              complete((NotFound, s"No image meta data found for image id $imageId"))
          }
        }
      }
    } ~ pathPrefix("anonymization") {
      path("anonymize") {
        post {
          entity(as[Seq[ImageTagValues]]) { imageTagValuesSeq =>
            complete {
              Future.sequence {
                imageTagValuesSeq.map(imageTagValues =>
                  anonymizeOne(apiUser, imageTagValues.imageId, imageTagValues.tagValues))
              }.map(_.flatMap(_.map(_.image)))
            }
          }
        }
      } ~ pathPrefix("keys") {
        pathEndOrSingleSlash {
          get {
            parameters(
              'startindex.as[Long] ? 0,
              'count.as[Long] ? 20,
              'orderby.as[String].?,
              'orderascending.as[Boolean] ? true,
              'filter.as[String].?) { (startIndex, count, orderBy, orderAscending, filter) =>
              onSuccess(anonymizationService.ask(GetAnonymizationKeys(startIndex, count, orderBy, orderAscending, filter))) {
                case AnonymizationKeys(anonymizationKeys) =>
                  complete(anonymizationKeys)
              }
            }
          }
        } ~ pathPrefix(LongNumber) { anonymizationKeyId =>
          pathEndOrSingleSlash {
            get {
              rejectEmptyResponse {
                complete(anonymizationService.ask(GetAnonymizationKey(anonymizationKeyId)).mapTo[Option[AnonymizationKey]])
              }
            } ~ delete {
              onSuccess(anonymizationService.ask(RemoveAnonymizationKey(anonymizationKeyId))) {
                case AnonymizationKeyRemoved(_) =>
                  complete(NoContent)
              }
            }
          } ~ path("images") {
            get {
              complete(anonymizationService.ask(GetImageIdsForAnonymizationKey(anonymizationKeyId)).mapTo[Seq[Long]].flatMap { imageIds =>
                Future.sequence {
                  imageIds.map { imageId =>
                    metaDataService.ask(GetImage(imageId)).mapTo[Option[Image]]
                  }
                }
              }.map(_.flatten))
            }
          }
        } ~ path("query") {
          post {
            entity(as[AnonymizationKeyQuery]) { query =>
              complete(anonymizationService.ask(QueryAnonymizationKeys(query)).mapTo[Seq[AnonymizationKey]])
            }
          }
        }
      }
    }

  def anonymizeOne(apiUser: ApiUser, imageId: Long, tagValues: Seq[TagValue]): Future[Option[MetaDataAdded]] = {
    metaDataService.ask(GetImage(imageId)).mapTo[Option[Image]].flatMap { imageMaybe =>
      imageMaybe.map { image =>
        metaDataService.ask(GetSourceForSeries(image.seriesId)).mapTo[Option[SeriesSource]].map { seriesSourceMaybe =>
          seriesSourceMaybe.map { seriesSource =>
            anonymizeData(image, seriesSource.source, storage, tagValues)
          }
        }.unwrap
      }.unwrap
    }
  }

}
