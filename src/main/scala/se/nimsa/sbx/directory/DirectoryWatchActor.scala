/*
 * Copyright 2016 Lars Edenbrandt
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

package se.nimsa.sbx.directory

import java.nio.file.{Files, Paths}

import akka.actor.{Actor, Props, Stash}
import akka.event.{Logging, LoggingReceive}
import akka.pattern.ask
import akka.util.Timeout
import org.dcm4che3.data.Attributes
import se.nimsa.sbx.anonymization.AnonymizationProtocol.ReverseAnonymization
import se.nimsa.sbx.app.GeneralProtocol._
import se.nimsa.sbx.dicom.DicomData
import se.nimsa.sbx.dicom.DicomHierarchy.Image
import se.nimsa.sbx.dicom.DicomUtil._
import se.nimsa.sbx.directory.DirectoryWatchProtocol._
import se.nimsa.sbx.log.SbxLog
import se.nimsa.sbx.metadata.MetaDataProtocol.{AddMetaData, MetaDataAdded}
import se.nimsa.sbx.storage.StorageProtocol.{AddDicomData, CheckDicomData, DicomDataAdded}

import scala.concurrent.Future
import scala.util.control.NonFatal
import scala.util.{Failure, Success}

class DirectoryWatchActor(watchedDirectory: WatchedDirectory,
                          implicit val timeout: Timeout,
                          metaDataServicePath: String = "../../MetaDataService",
                          storageServicePath: String = "../../StorageService",
                          anonymizationServicePath: String = "../../AnonymizationService") extends Actor with Stash {

  val log = Logging(context.system, this)

  val watchServiceTask = new DirectoryWatch(self)

  val watchThread = new Thread(watchServiceTask, "WatchService")

  val storageService = context.actorSelection(storageServicePath)
  val metaDataService = context.actorSelection(metaDataServicePath)
  val anonymizationService = context.actorSelection(anonymizationServicePath)

  implicit val system = context.system
  implicit val ec = context.dispatcher

  case object DicomDataProcessed

  override def preStart() {
    watchThread.setDaemon(true)
    watchThread.start()
    watchServiceTask watchRecursively Paths.get(watchedDirectory.path)
  }

  override def postStop() {
    watchThread.interrupt()
  }

  def receive = LoggingReceive {

    case FileAddedToWatchedDirectory(path) =>
      if (Files.isRegularFile(path)) {
        val dicomData = loadDicomData(path, withPixelData = true)
        val source = Source(SourceType.DIRECTORY, watchedDirectory.name, watchedDirectory.id)
        context.become(waitForDatasetProcessed)
        checkDicomData(dicomData).flatMap { _ =>
          reverseAnonymization(dicomData.attributes).flatMap { reversedAttributes =>
            val reversedDicomData = DicomData(reversedAttributes, dicomData.metaInformation)
            addMetadata(reversedAttributes, source).flatMap { image =>
              addDicomData(reversedDicomData, source, image).map { _ =>
              }
            }
          }
        }.onComplete {
          case Success(_) =>
            self ! DicomDataProcessed
          case Failure(NonFatal(e)) =>
            SbxLog.error("Directory", s"Could not add file: ${e.getMessage}")
            self ! DicomDataProcessed
        }
      }

  }

  def waitForDatasetProcessed: Receive = LoggingReceive {
    case _: FileAddedToWatchedDirectory =>
      stash()
    case DicomDataProcessed =>
      context.unbecome()
      unstashAll()
  }

  def addMetadata(attributes: Attributes, source: Source): Future[Image] =
    metaDataService.ask(
      AddMetaData(attributes, source))
      .mapTo[MetaDataAdded]
      .map(_.image)

  def addDicomData(dicomData: DicomData, source: Source, image: Image): Future[Boolean] =
    storageService.ask(AddDicomData(dicomData, source, image))
      .mapTo[DicomDataAdded]
      .map(_.overwrite)

  def checkDicomData(dicomData: DicomData): Future[Boolean] =
    storageService.ask(CheckDicomData(dicomData, useExtendedContexts = false)).mapTo[Boolean]

  def reverseAnonymization(attributes: Attributes): Future[Attributes] =
    anonymizationService.ask(ReverseAnonymization(attributes)).mapTo[Attributes]

}

object DirectoryWatchActor {
  def props(watchedDirectory: WatchedDirectory, timeout: Timeout): Props = Props(new DirectoryWatchActor(watchedDirectory, timeout))
}
