package se.nimsa.sbx.app.routing

import java.io.File
import scala.slick.driver.H2Driver
import org.dcm4che3.data.Tag
import org.dcm4che3.data.VR
import org.scalatest.FlatSpec
import org.scalatest.Matchers
import se.nimsa.sbx.anonymization.AnonymizationDAO
import se.nimsa.sbx.anonymization.AnonymizationProtocol._
import se.nimsa.sbx.dicom.DicomHierarchy._
import se.nimsa.sbx.dicom.DicomUtil
import se.nimsa.sbx.dicom.ImageAttribute
import se.nimsa.sbx.util.TestUtil
import spray.http.BodyPart
import spray.http.ContentTypes
import spray.http.HttpData
import spray.http.MultipartFormData
import spray.http.StatusCodes._
import spray.httpx.SprayJsonSupport._
import spray.httpx.unmarshalling.BasicUnmarshallers.ByteArrayUnmarshaller
import se.nimsa.sbx.metadata.MetaDataDAO

class ImageRoutesTest extends FlatSpec with Matchers with RoutesTestBase {

  def dbUrl() = "jdbc:h2:mem:imageroutestest;DB_CLOSE_DELAY=-1"

  val metaDataDao = new MetaDataDAO(H2Driver)
  
  override def afterEach() {
    db.withSession { implicit session =>
      metaDataDao.clear
    }
  }
  
  "Image routes" should "return 201 Created when adding an image using multipart form data" in {
    val file = TestUtil.testImageFile
    val mfd = MultipartFormData(Seq(BodyPart(file, "file")))
    PostAsUser("/api/images", mfd) ~> routes ~> check {
      status should be(Created)
      val image = responseAs[Image]
      image.id should be(1)
    }
  }

  it should "return 201 Created when adding an image as a byte array" in {
    PostAsUser("/api/images", HttpData(TestUtil.testImageByteArray)) ~> routes ~> check {
      status should be(Created)
      val image = responseAs[Image]
      image.id should be(2)
    }
  }

  it should "return 200 OK when adding an image that has already been added" in {
    PostAsUser("/api/images", HttpData(TestUtil.testImageByteArray)) ~> routes ~> check {
      status should be(Created)
    }
    PostAsUser("/api/images", HttpData(TestUtil.testImageByteArray)) ~> routes ~> check {
      status should be(OK)
    }
  }

  it should "allow fetching the image again" in {
    val image = PostAsUser("/api/images", HttpData(TestUtil.testImageByteArray)) ~> routes ~> check { responseAs[Image] }
    GetAsUser(s"/api/images/${image.id}") ~> routes ~> check {
      contentType should be(ContentTypes.`application/octet-stream`)
      val dataset = DicomUtil.loadDataset(responseAs[Array[Byte]], true)
      dataset should not be (null)
    }
  }

  it should "return 404 NotFound when requesting an image that does not exist" in {
    GetAsUser("/api/images/2") ~> routes ~> check {
      status should be(NotFound)
    }
  }

  it should "return 400 BadRequest when adding an invalid image" in {
    val file = new File("some file that does not exist")
    val mfd = MultipartFormData(Seq(BodyPart(file, "file")))
    PostAsUser("/api/images", mfd) ~> routes ~> check {
      status should be(BadRequest)
    }
  }

  it should "return 200 OK and a non-empty list of attributes when listing attributes for an image" in {
    val image = PostAsUser("/api/images", HttpData(TestUtil.testImageByteArray)) ~> routes ~> check { responseAs[Image] }
    GetAsUser(s"/api/images/${image.id}/attributes") ~> routes ~> check {
      status should be(OK)
      responseAs[List[ImageAttribute]].size should be > (0)
    }
  }

  it should "return 404 NotFound when listing attributes for an image that does not exist" in {
    GetAsUser("/api/images/666/attributes") ~> routes ~> check {
      status should be(NotFound)
    }
  }

  it should "return 200 OK and a non-empty array of bytes when requesting a png represenation of an image" in {
    val image = PostAsUser("/api/images", HttpData(TestUtil.testImageByteArray)) ~> routes ~> check { responseAs[Image] }
    GetAsUser(s"/api/images/${image.id}/png") ~> routes ~> check {
      status should be(OK)
      responseAs[Array[Byte]] should not be empty
    }
  }

  it should "return 404 NotFound when requesting a png for an image that does not exist" in {
    GetAsUser("/api/images/666/png") ~> routes ~> check {
      status should be(NotFound)
    }
  }

  it should "return 201 Created when adding a jpeg image to a study" in {
    val image = PostAsUser("/api/images", HttpData(TestUtil.testImageByteArray)) ~> routes ~> check { responseAs[Image] }
    val series = GetAsUser(s"/api/metadata/series/${image.seriesId}") ~> routes ~> check { responseAs[Series] }
    val study = GetAsUser(s"/api/metadata/studies/${series.studyId}") ~> routes ~> check { responseAs[Study] }
    PostAsUser(s"/api/images/jpeg?studyid=${study.id}", HttpData(TestUtil.jpegByteArray)) ~> routes ~> check {
      status shouldBe Created
      val image = responseAs[Image]
      image shouldNot be(null)
    }
  }

  it should "return 404 NotFound when adding a jpeg image to a study that does not exist" in {
    PostAsUser("/api/images/jpeg?studyid=666", HttpData(TestUtil.jpegByteArray)) ~> routes ~> check {
      status shouldBe NotFound
    }
  }

  it should "return 400 BadRequest when adding an invalid jpeg image" in {
    val image = PostAsUser("/api/images", HttpData(TestUtil.testImageByteArray)) ~> routes ~> check { responseAs[Image] }
    val series = GetAsUser(s"/api/metadata/series/${image.seriesId}") ~> routes ~> check { responseAs[Series] }
    val study = GetAsUser(s"/api/metadata/studies/${series.studyId}") ~> routes ~> check { responseAs[Study] }
    PostAsUser(s"/api/images/jpeg?studyid=${study.id}", HttpData(Array[Byte](1, 2, 3, 4))) ~> routes ~> check {
      status shouldBe BadRequest
    }
  }

  it should "return 200 OK and a non-empty array of bytes when requesting png data for a secondary capture jpeg image" in {
    val image = PostAsUser("/api/images", HttpData(TestUtil.testImageByteArray)) ~> routes ~> check { responseAs[Image] }
    val series = GetAsUser(s"/api/metadata/series/${image.seriesId}") ~> routes ~> check { responseAs[Series] }
    val study = GetAsUser(s"/api/metadata/studies/${series.studyId}") ~> routes ~> check { responseAs[Study] }
    val pngImage = PostAsUser(s"/api/images/jpeg?studyid=${study.id}", HttpData(TestUtil.jpegByteArray)) ~> routes ~> check { responseAs[Image] }
    GetAsUser(s"/api/images/${pngImage.id}/png") ~> routes ~> check {
      status should be(OK)
      responseAs[Array[Byte]] should not be empty
    }
  }

  it should "support deleting an image" in {
    val image = PostAsUser("/api/images", HttpData(TestUtil.testImageByteArray)) ~> routes ~> check { responseAs[Image] }
    GetAsUser(s"/api/metadata/images/${image.id}") ~> routes ~> check {
      status should be(OK)
    }
    DeleteAsUser(s"/api/images/${image.id}") ~> routes ~> check {
      status should be(NoContent)
    }
    GetAsUser(s"/api/metadata/images/${image.id}") ~> routes ~> check {
      status should be(NotFound)
    }
  }

  it should "return 204 NoContent when deleting a sequence of images" in {
    val image =
      PostAsUser("/api/images", HttpData(TestUtil.testImageByteArray)) ~> routes ~> check {
        status shouldBe Created
        responseAs[Image]
      }

    GetAsUser(s"/api/images/${image.id}") ~> routes ~> check {
      status shouldBe OK
    }

    PostAsUser("/api/images/delete", Seq(image.id)) ~> routes ~> check {
      status shouldBe NoContent
    }

    GetAsUser(s"/api/images/${image.id}") ~> routes ~> check {
      status shouldBe NotFound
    }
  }

  it should "return 204 NoContent even though one or more image ids are invalid when deleting a sequence of images (idempotence)" in {
    val image =
      PostAsUser("/api/images", HttpData(TestUtil.testImageByteArray)) ~> routes ~> check {
        status shouldBe Created
        responseAs[Image]
      }

    PostAsUser("/api/images/delete", Seq(image.id, 666, 667, 668)) ~> routes ~> check {
      status shouldBe NoContent
    }
  }
}