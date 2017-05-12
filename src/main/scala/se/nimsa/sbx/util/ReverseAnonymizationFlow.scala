package se.nimsa.sbx.util

import akka.stream.scaladsl.Flow
import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import akka.stream.{Attributes, FlowShape, Inlet, Outlet}
import org.dcm4che3.data.{SpecificCharacterSet, Tag}
import se.nimsa.dcm4che.streams.DicomParts._

/**
  * A flow which expects a DicomMetaPart as first part, and does reverse anonymization based on anonymization data lookup in DB.
  */
class ReverseAnonymizationFlow() extends GraphStage[FlowShape[DicomPart, DicomPart]] {
  val in = Inlet[DicomPart]("DicomAttributeBufferFlow.in")
  val out = Outlet[DicomPart]("DicomAttributeBufferFlow.out")
  override val shape = FlowShape.of(in, out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
    var metaData: Option[DicomMetaPart] = None
    val REVERSE_ANON_TAGS = Seq(Tag.PatientName,
      Tag.PatientID,
      Tag.PatientBirthDate,
      Tag.PatientIdentityRemoved,
      Tag.StudyInstanceUID,
      Tag.StudyDescription,
      Tag.StudyID,
      Tag.AccessionNumber,
      Tag.SeriesInstanceUID,
      Tag.SeriesDescription,
      Tag.ProtocolName,
      Tag.FrameOfReferenceUID
    )
    var currentAttribute: Option[DicomAttribute] = None


    def isAnonymized = if (metaData.isDefined) {
      metaData.get.isAnonymized
    } else {
      false
    }


    setHandlers(in, out, new InHandler with OutHandler {

      override def onPull(): Unit = {
        pull(in)
      }

      override def onPush(): Unit = {

        // do reverse anon if:
        // metaData is defined and data is anonymized
        // anomymization keys found in DB
        // tag specifies attribute that needs to be reversed
        def needReverseAnon(tag: Int): Boolean = {
          canDoReverseAnon && REVERSE_ANON_TAGS.contains(tag)
        }

        def canDoReverseAnon: Boolean = metaData.exists(m => m.isAnonymized && m.anonKeys.isDefined)

        val part = grab(in)

        part match {
          case metaPart: DicomMetaPart =>
            metaData = Some(metaPart)
            // FIXME: remove println
            println(">>>> grabbed meta, isAnon: " + isAnonymized)
            println(">>>> grabbed meta, canDoReverse: " + canDoReverseAnon)
            push(out, metaPart)

          case header: DicomHeader if needReverseAnon(header.tag)  =>
            currentAttribute = Some(DicomAttribute(header, Seq.empty))
            pull(in)

          case header: DicomHeader =>
            currentAttribute = None
            push(out, part)


          case valueChunk: DicomValueChunk if currentAttribute.isDefined && canDoReverseAnon =>
            currentAttribute = currentAttribute.map(attribute => attribute.copy(valueChunks = attribute.valueChunks :+ valueChunk))
            val cs = if (metaData.get.specificCharacterSet.isDefined) metaData.get.specificCharacterSet.get else SpecificCharacterSet.ASCII
            if (valueChunk.last) {

              val updatedAttribute = currentAttribute.get.header.tag match {
                case Tag.PatientName =>
                  currentAttribute.get.withUpdatedStringValue(metaData.get.anonKeys.get.patientName, cs)

                case Tag.PatientID =>
                  currentAttribute.get.withUpdatedStringValue(metaData.get.anonKeys.get.patientID, cs)

                case Tag.PatientBirthDate =>
                  currentAttribute.get.withUpdatedDateValue(metaData.get.anonKeys.get.patientBirthDate) // ASCII

                case Tag.PatientIdentityRemoved =>
                  currentAttribute.get.withUpdatedStringValue("NO") // ASCII

                case Tag.StudyInstanceUID =>
                  currentAttribute.get.withUpdatedStringValue(metaData.get.anonKeys.get.studyInstanceUID) // ASCII

                case Tag.StudyDescription =>
                  currentAttribute.get.withUpdatedStringValue(metaData.get.anonKeys.get.studyDescription, cs)

                case Tag.StudyID =>
                  currentAttribute.get.withUpdatedStringValue(metaData.get.anonKeys.get.studyID, cs)

                case Tag.AccessionNumber =>
                  currentAttribute.get.withUpdatedStringValue(metaData.get.anonKeys.get.accessionNumber, cs)

                case Tag.SeriesInstanceUID =>
                  currentAttribute.get.withUpdatedStringValue(metaData.get.anonKeys.get.seriesInstanceUID) // ASCII

                case Tag.SeriesDescription =>
                  currentAttribute.get.withUpdatedStringValue(metaData.get.anonKeys.get.seriesDescription, cs)

                case Tag.ProtocolName =>
                  currentAttribute.get.withUpdatedStringValue(metaData.get.anonKeys.get.protocolName, cs)

                case Tag.FrameOfReferenceUID =>
                  currentAttribute.get.withUpdatedStringValue(metaData.get.anonKeys.get.frameOfReferenceUID) // ASCII

                case _ =>
                  currentAttribute.get
              }
              // FIXME remove println
              println(">>>> currentAttr: " + currentAttribute.get.header + " - " + currentAttribute.get.bytes.decodeString("ASCII"))
              println(">>>> updatedAttr: " + updatedAttribute.header + " - " + updatedAttribute.bytes.decodeString("ASCII"))
              emitMultiple(out, (updatedAttribute.header +: updatedAttribute.valueChunks).iterator)
              currentAttribute = None
            } else {
              pull(in)
            }

          case part: DicomPart =>
            push(out, part)

        }
      }

      override def onUpstreamFinish(): Unit = {
        //FIXME
        complete(out)
      }

    })
  }

}

object ReverseAnonymizationFlow {
  val reverseAnonFlow = Flow[DicomPart].via(new ReverseAnonymizationFlow())
}







