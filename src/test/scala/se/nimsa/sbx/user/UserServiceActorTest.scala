package se.nimsa.sbx.user


import akka.actor.ActorSystem
import akka.testkit.{ImplicitSender, TestActorRef, TestKit}
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, Matchers, WordSpecLike}
import se.nimsa.sbx.app.DbProps
import se.nimsa.sbx.user.UserProtocol._

import scala.slick.driver.H2Driver
import scala.slick.jdbc.JdbcBackend.Database

class UserServiceActorTest(_system: ActorSystem) extends TestKit(_system) with ImplicitSender
    with WordSpecLike with Matchers with BeforeAndAfterAll with BeforeAndAfterEach {

  def this() = this(ActorSystem("UserServiceActorTestSystem"))

  val db = Database.forURL("jdbc:h2:mem:userserviceactortest;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
  val dbProps = DbProps(db, H2Driver)

  val dao = new UserDAO(H2Driver)

  db.withSession { implicit session =>
    dao.create
  }

  val userService = TestActorRef(new UserServiceActor(dbProps, "admin", "admin", 1000000))
  val userActor = userService.underlyingActor

  override def afterEach() =
    db.withSession { implicit session =>
      dao.clear
    }

  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }

  "A UserServiceActor" should {

    "cleanup expired sessions regularly" in {
      db.withSession { implicit session =>

        val user = dao.insert(ApiUser(-1, "user", UserRole.USER).withPassword("pass"))
        dao.insertSession(ApiSession(-1, user.id, "token1", "ip1", "user agent1", System.currentTimeMillis))
        dao.insertSession(ApiSession(-1, user.id, "token2", "ip2", "user agent2", 0))

        dao.listUsers(0, 10) should have length 2
        dao.listSessions should have length 2

        userActor.removeExpiredSessions()

        dao.listSessions should have length 1
      }
    }

    "refresh a non-expired session defined by a token, ip and user agent" in {
      db.withSession { implicit session =>

        val user = dao.insert(ApiUser(-1, "user", UserRole.USER).withPassword("pass"))
        val sessionTime = System.currentTimeMillis - 1000
        dao.insertSession(ApiSession(-1, user.id, "token", "ip", "user agent", sessionTime))

        userActor.getAndRefreshUser(AuthKey(Some("token"), Some("ip"), Some("user agent")))

        val optionalSession = dao.userSessionByTokenIpAndUserAgent("token", "ip", "user agent")
        optionalSession.isDefined shouldBe true
        optionalSession.get._2.updated shouldBe >(sessionTime)
      }
    }

    "not refresh an expired session defined by a token, ip and user agent" in {
      db.withSession { implicit session =>

        val user = dao.insert(ApiUser(-1, "user", UserRole.USER).withPassword("pass"))
        val sessionTime = 1000
        dao.insertSession(ApiSession(-1, user.id, "token", "ip", "user agent", sessionTime))

        userActor.getAndRefreshUser(AuthKey(Some("token"), Some("ip"), Some("user agent")))

        val optionalSession = dao.userSessionByTokenIpAndUserAgent("token", "ip", "user agent")
        optionalSession.isDefined shouldBe true
        optionalSession.get._2.updated shouldBe sessionTime
      }
    }

    "create a session if none exists and update it if one exists" in {
      db.withSession { implicit session =>

        val user = dao.insert(ApiUser(-1, "user", UserRole.USER).withPassword("pass"))

        dao.listSessions should have length 0

        val session1 = userActor.createOrUpdateSession(user, "ip", "userAgent")
        dao.listSessions should have length 1

        Thread.sleep(100)

        val session2 = userActor.createOrUpdateSession(user, "ip", "userAgent")
        dao.listSessions should have length 1
        session2.updated shouldBe >(session1.updated)
      }
    }

    "remove a session based on user id, IP and user agent when logging out" in {
      db.withSession { implicit session =>
        val user = dao.insert(ApiUser(-1, "user", UserRole.USER).withPassword("pass"))
        val session1 = userActor.createOrUpdateSession(user, "ip", "userAgent")
        dao.listSessions should have length 1
        userActor.deleteSession(user, AuthKey(Some(session1.token), Some("Other IP"), Some(session1.userAgent)))
        dao.listSessions should have length 1
        userActor.deleteSession(user, AuthKey(Some(session1.token), Some(session1.ip), Some(session1.userAgent)))
        dao.listSessions should have length 0
      }
    }

    "not create more than one session when logging in twice" in {
      db.withSession { implicit session =>
        dao.insert(ApiUser(-1, "user", UserRole.USER).withPassword("pass"))

        userService ! Login(UserPass("user", "pass"), AuthKey(None, Some("ip"), Some("userAgent")))
        expectMsgType[LoggedIn]
        dao.listUsers (0, 10) should have length 1
        dao.listSessions should have length 1

        userService ! Login(UserPass("user", "pass"), AuthKey(None, Some("ip"), Some("userAgent")))
        expectMsgType[LoggedIn]
        dao.listUsers(0, 10) should have length 1
        dao.listSessions should have length 1
      }
    }

    "not allow logging in if credentials are invalid" in {
      db.withSession { implicit session =>
        dao.insert(ApiUser(-1, "user", UserRole.USER).withPassword("pass"))
        userService ! Login(UserPass("user", "incorrect password"), AuthKey(None, Some("ip"), Some("userAgent")))
        expectMsg(LoginFailed)
      }
    }

    "not allow logging in if information on IP address and/or user agent is missing" in {
      db.withSession { implicit session =>
        dao.insert(ApiUser(-1, "user", UserRole.USER).withPassword("pass"))
        
        userService ! Login(UserPass("user", "pass"), AuthKey(None, None, Some("userAgent")))
        expectMsg(LoginFailed)
        userService ! Login(UserPass("user", "pass"), AuthKey(None, Some("ip"), None))
        expectMsg(LoginFailed)
        userService ! Login(UserPass("user", "pass"), AuthKey(None, None, None))
        expectMsg(LoginFailed)
      }
    }

  }
}