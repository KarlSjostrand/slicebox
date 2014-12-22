package se.vgregion.app

import java.nio.file.Files
import java.nio.file.Paths
import org.scalatest.FlatSpec
import org.scalatest.Matchers
import se.vgregion.dicom.DicomDispatchProtocol._
import spray.http.StatusCodes.OK
import se.vgregion.util.Message

class DirectoryRoutesTest extends FlatSpec with Matchers with RoutesTestBase {

  initialize()

  "The system" should "return a monitoring message when asked to monitor a new directory" in {
    val tempDir = Files.createTempDirectory("slicebox-temp-dir-")
    val watchDir = WatchDirectory(tempDir.toString)

    Put("/api/directory", watchDir) ~> routes ~> check {
      responseAs[Message].message should be(s"Now watching directory $tempDir")
    }

  }

  it should "return an empty list of images when monitoring an empty directory and return one image after a file has been copied to that directory" in {
    val tempDir = Files.createTempDirectory("slicebox-temp-dir-")
    val watchDir = WatchDirectory(tempDir.toString)

    Put("/api/directory", watchDir) ~> routes ~> check {
      responseAs[Message].message should be(s"Now watching directory $tempDir")
    }

    Get("/api/metadata/allimages") ~> routes ~> check {
      status should be(OK)
      responseAs[Images].images.size should be (0)
    }

    val fileName = "anon270.dcm"
    val dcmPath = Paths.get(getClass().getResource(fileName).toURI())
    Files.copy(dcmPath, tempDir.resolve(fileName))

    // just sleep for a tiny bit and let the OS find out there was a new file in the monitored directory. It will be picked up and put
    // in the database
    Thread.sleep(500)

    Get("/api/metadata/allimages") ~> routes ~> check {
      status should be(OK)
      responseAs[Images].images.size should be (1)
    }
  }

}