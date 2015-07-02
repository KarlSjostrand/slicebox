package se.nimsa.sbx.app

import org.scalatest.FlatSpec
import org.scalatest.Matchers
import spray.http.StatusCodes.OK
import spray.testkit.ScalatestRouteTest
import scala.concurrent.duration.DurationInt
import spray.httpx.SprayJsonSupport._
import se.nimsa.sbx.dicom.DicomHierarchy.Patient
import se.nimsa.sbx.dicom.DicomProtocol._
import scala.slick.driver.H2Driver
import scala.slick.jdbc.JdbcBackend.Database
import se.nimsa.sbx.dicom.DicomMetaDataDAO
import se.nimsa.sbx.dicom.DicomPropertyValue._
import se.nimsa.sbx.dicom.DicomProtocol._
import se.nimsa.sbx.dicom.DicomHierarchy._

class MetaDataRoutesTest extends FlatSpec with Matchers with RoutesTestBase {

  def dbUrl() = "jdbc:h2:mem:metadataroutestest;DB_CLOSE_DELAY=-1"
  
  val dao = new DicomMetaDataDAO(H2Driver)
  
  val pat1 = Patient(-1, PatientName("p1"), PatientID("s1"), PatientBirthDate("2000-01-01"), PatientSex("M"))
  val pat2 = Patient(-1, PatientName("p2"), PatientID("s2"), PatientBirthDate("2001-01-01"), PatientSex("F"))
  val study1 = Study(-1, -1, StudyInstanceUID("stuid1"), StudyDescription("stdesc1"), StudyDate("19990101"), StudyID("stid1"), AccessionNumber("acc1"), PatientAge("12Y"))
  val equipment1 = Equipment(-1, Manufacturer("manu1"), StationName("station1"))
  val for1 = FrameOfReference(-1, FrameOfReferenceUID("frid1"))
  val series1 = Series(-1, -1, -1, -1, SeriesInstanceUID("seuid1"), SeriesDescription("sedesc1"), SeriesDate("19990101"), Modality("NM"), ProtocolName("prot1"), BodyPartExamined("bodypart1"))
  
  before {
    db.withSession { implicit session =>
      dao.create
    }
  }
  
  after {
    db.withSession { implicit session =>
      dao.drop
    }
  }
  
  "Meta data routs" should "return 200 OK and return an empty list of images when asking for all images" in {
    GetAsUser("/api/metadata/patients") ~> routes ~> check {
      status should be(OK)
      responseAs[List[Patient]].size should be (0)
    }
  }

  it should "return 200 OK and return patient when querying patients" in {
    // given
    db.withSession { implicit session =>
      dao.insert(pat1)
    }
    
    // then
    val queryProperties = Seq(QueryProperty("PatientName", QueryOperator.EQUALS, "p1"))
    val query = Query(0, 10, None, false, queryProperties)
    
    PostAsUser("/api/metadata/patients/query", query) ~> routes ~> check {
      status should be(OK)
      responseAs[List[Patient]].size should be (1)
    }
  }
  
  it should "be able to do like querying" in {
    // given
    db.withSession { implicit session =>
      dao.insert(pat1)
      dao.insert(pat2)
    }
    
    // then
    val query = Query(0, 10, Some("PatientName"), false, Seq(QueryProperty("PatientName", QueryOperator.LIKE, "%p%")))
    
    PostAsUser("/api/metadata/patients/query", query) ~> routes ~> check {
      status should be(OK)
      val patients = responseAs[List[Patient]]
      
      patients.size should be(2)
      patients(0).patientName.value should be("p2")
      patients(1).patientName.value should be("p1")
    }
  }
  
  it should "be able to sort when querying" in {
    // given
    db.withSession { implicit session =>
      dao.insert(pat1)
      dao.insert(pat2)
    }
    
    // then
    val query = Query(0, 10, Some("PatientName"), false, Seq[QueryProperty]())
    
    PostAsUser("/api/metadata/patients/query", query) ~> routes ~> check {
      status should be(OK)
      val patients = responseAs[List[Patient]]
      
      patients.size should be(2)
      patients(0).patientName.value should be("p2")
      patients(1).patientName.value should be("p1")
    }
  }
  
  it should "be able to page results when querying" in {
    // given
    db.withSession { implicit session =>
      dao.insert(pat1)
      dao.insert(pat2)
    }
    
    // then
    val query = Query(1, 1, Some("PatientName"), false, Seq[QueryProperty]())
    
    PostAsUser("/api/metadata/patients/query", query) ~> routes ~> check {
      status should be(OK)
      val patients = responseAs[List[Patient]]
      
      patients.size should be(1)
      patients(0).patientName.value should be("p1")
    }
  }
  
  it should "return 200 OK and return studies when querying studies" in {
    // given
    db.withSession { implicit session =>
      val dbPat = dao.insert(pat1)
      val dbStudy = dao.insert(study1.copy(patientId = dbPat.id))
    }
    
    // then
    val queryProperties = Seq(QueryProperty("StudyInstanceUID", QueryOperator.EQUALS, "stuid1"))
    val query = Query(0, 10, None, false, queryProperties)
    
    PostAsUser("/api/metadata/studies/query", query) ~> routes ~> check {
      status should be(OK)
      responseAs[List[Study]].size should be (1)
    }
  }
  
  it should "return 200 OK and return series when querying series" in {
    // given
    db.withSession { implicit session =>
      val dbPat = dao.insert(pat1)
      val dbStudy = dao.insert(study1.copy(patientId = dbPat.id))
      val dbEquipment = dao.insert(equipment1)
      val dbFor = dao.insert(for1)
      dao.insert(series1.copy(studyId = dbStudy.id, equipmentId = dbEquipment.id, frameOfReferenceId = dbFor.id))
    }
    
    // then
    val queryProperties = Seq(QueryProperty("SeriesInstanceUID", QueryOperator.EQUALS, "seuid1"))
    val query = Query(0, 10, None, false, queryProperties)
    
    PostAsUser("/api/metadata/series/query", query) ~> routes ~> check {
      status should be(OK)
      responseAs[List[Series]].size should be (1)
    }
  }
}