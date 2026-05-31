//> using scala 3.6.3
//> using dep org.typelevel::cats-effect::3.5.7
//> using dep org.http4s::http4s-ember-server::0.23.30
//> using dep org.http4s::http4s-dsl::0.23.30
//> using dep com.armanbilge::porcupine::0.0.1
//> using dep org.xerial:sqlite-jdbc:3.49.1.0

import cats.effect.*
import cats.syntax.all.*
import com.comcast.ip4s.*
import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.staticcontent.*
import porcupine.*
import porcupine.Codec.*
import porcupine.sql

import scala.util.Random

type Db = Database[IO]

case class Account(id: Long, name: String, iban: String, balance: Double, currency: String)

object Main extends IOApp.Simple:

  private val selectAccount =
    sql"select id, name, iban, balance, currency from accounts where id = $integer"
      .query(integer *: text *: text *: real *: text *: nil)

  private val selectByIban =
    sql"select id, name, iban, balance, currency from accounts where iban = $text"
      .query(integer *: text *: text *: real *: text *: nil)

  private val insertAccount =
    sql"insert into accounts (name, iban, balance, currency) values ($text, $text, $real, $text)".command

  private val updateBalance =
    sql"update accounts set balance = $real where id = $integer".command

  private val insertTransfer =
    sql"insert into transfers (from_iban, to_iban, amount, message) values ($text, $text, $real, $text)".command

  def run: IO[Unit] =
    Database.open[IO]("data/payto.db").use { db =>
      initDb(db) >> startServer(db)
    }

  private def initDb(db: Db): IO[Unit] =
    db.execute(sql"""
      create table if not exists accounts (
        id integer primary key autoincrement,
        name text not null,
        iban text not null unique,
        balance real not null,
        currency text not null default 'EUR'
      )
    """.command) >>
      db.execute(sql"""
        create table if not exists transfers (
          id integer primary key autoincrement,
          from_iban text not null,
          to_iban text not null,
          amount real not null,
          message text,
          created_at text not null default (datetime('now'))
        )
      """.command) >>
      db.option(selectByIban, "DE75512108001245126199").flatMap {
        case Some(_) => IO.unit
        case None =>
          db.execute(insertAccount, ("Demo Shop", "DE75512108001245126199", 0.0, "EUR"))
      }

  private def startServer(db: Db): IO[Unit] =
    val api = HttpRoutes.of[IO] {
      case GET -> Root / "api" / "seller" =>
        loadByIban(db, "DE75512108001245126199").flatMap {
          case Some(acc) => jsonOk(accJson(acc))
          case None      => NotFound(jsonErr("Seller account not found"))
        }

      case req @ GET -> Root / "api" / "me" =>
        me(db, req)

      case req @ GET -> Root / "api" / "account" =>
        req.params.get("iban") match
          case Some(iban) => lookup(db, iban)
          case None       => BadRequest(jsonErr("Missing iban parameter"))

      case req @ POST -> Root / "api" / "pay" =>
        pay(db, req)
    }

    val static = fileService[IO](FileService.Config("web", ""))
    val app = (api <+> static).orNotFound

    EmberServerBuilder
      .default[IO]
      .withHost(host"0.0.0.0")
      .withPort(port"8080")
      .withHttpApp(app)
      .build
      .use(_ => IO.println("Serving http://localhost:8080  (db: payto.db)") *> IO.never)

  private def me(db: Db, req: Request[IO]): IO[Response[IO]] =
    req.cookies.find(_.name == "account_id").flatMap(_.content.toLongOption) match
      case Some(id) =>
        loadAccount(db, id).flatMap {
          case Some(acc) => jsonOk(accJson(acc)).map(_.addCookie(cookie(id)))
          case None      => createAccount(db)
        }
      case None => createAccount(db)

  private def createAccount(db: Db): IO[Response[IO]] =
    val name = s"User ${Random.between(1000, 9999)}"
    val iban = Iban.generate()
    db.execute(insertAccount, (name, iban, 4250.0, "EUR")) >>
      db.execute(sql"select last_insert_rowid()".query(integer)).flatMap {
        case id :: Nil =>
          loadAccount(db, id).flatMap {
            case Some(acc) => jsonOk(accJson(acc)).map(_.addCookie(cookie(id)))
            case None      => InternalServerError("Failed to create account")
          }
        case _ => InternalServerError("Failed to create account")
      }

  private def lookup(db: Db, iban: String): IO[Response[IO]] =
    val normalized = iban.replace(" ", "").toUpperCase
    db.option(selectByIban, normalized).flatMap {
      case Some((_, name, ib, _, _)) => Ok(jsonStr("name", name, "iban", ib))
      case None                      => NotFound(jsonErr(s"Unknown IBAN: $normalized"))
    }

  private def pay(db: Db, req: Request[IO]): IO[Response[IO]] =
    val fromId = req.cookies.find(_.name == "account_id").flatMap(_.content.toLongOption)
    if fromId.isEmpty then BadRequest(jsonErr("No account — reload the page"))
    else
      req.as[String].flatMap { body =>
        val toIban = jsonStr(body, "toIban").map(_.replace(" ", "").toUpperCase)
        val amount = jsonNum(body, "amount")
        (fromId, toIban, amount) match
          case (Some(fid), Some(to), Some(amt)) =>
            (for {
              from <- loadAccount(db, fid)
              toAcc <- loadByIban(db, to)
            } yield (from, toAcc)).flatMap {
              case (Some(f), Some(t)) => transfer(db, f, t, amt, jsonStr(body, "message").getOrElse(""))
              case _                    => BadRequest(jsonErr("Unknown recipient IBAN"))
            }
          case _ => BadRequest(jsonErr("Missing toIban or amount"))
      }

  private def transfer(db: Db, from: Account, to: Account, amount: Double, message: String): IO[Response[IO]] =
    if amount <= 0 then BadRequest(jsonErr("Amount must be positive"))
    else if from.iban == to.iban then BadRequest(jsonErr("Cannot pay yourself"))
    else if from.balance < amount then BadRequest(jsonErr("Insufficient funds"))
    else
      val newFrom = from.balance - amount
      val newTo = to.balance + amount
      db.execute(updateBalance, (newFrom, from.id)) >>
        db.execute(updateBalance, (newTo, to.id)) >>
        db.execute(insertTransfer, (from.iban, to.iban, amount, message)) >>
        Ok(s"""{"ok":true,"balance":$newFrom,"currency":"${from.currency}"}""")

  private def loadAccount(db: Db, id: Long): IO[Option[Account]] =
    db.option(selectAccount, id).map(_.map(toAccount))

  private def loadByIban(db: Db, iban: String): IO[Option[Account]] =
    db.option(selectByIban, iban).map(_.map(toAccount))

  private def toAccount(t: (Long, String, String, Double, String)): Account =
    val (id, name, iban, balance, currency) = t
    Account(id, name, iban, balance, currency)

  private def cookie(id: Long) =
    ResponseCookie("account_id", id.toString, path = Some("/"), maxAge = Some(365 * 24 * 3600))

  private def accJson(a: Account) =
    s"""{"id":${a.id},"name":"${esc(a.name)}","iban":"${a.iban}","balance":${a.balance},"currency":"${a.currency}"}"""

  private def jsonOk(body: String) = Ok(body)

  private def jsonStr(key: String, v1: String, k2: String, v2: String) =
    s"""{"$key":"${esc(v1)}","$k2":"$v2"}"""

  private def jsonErr(msg: String) = s"""{"ok":false,"error":"${esc(msg)}"}"""

  private def esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")

  private def jsonStr(body: String, key: String): Option[String] =
    s""""$key"\\s*:\\s*"([^"]*)"""".r.findFirstMatchIn(body).map(_.group(1))

  private def jsonNum(body: String, key: String): Option[Double] =
    s""""$key"\\s*:\\s*([0-9.]+)""".r.findFirstMatchIn(body).map(_.group(1).toDouble)

object Iban:
  private val rng = Random

  def generate(country: String = "DE"): String =
    val cc = country.toUpperCase
    val bban = (1 to 18).map(_ => rng.nextInt(10)).mkString
    s"$cc${checkDigits(cc, bban)}$bban"

  private def checkDigits(country: String, bban: String): String =
    val s = bban + country.map(c => (c.toInt - 55).toString).mkString + "00"
    f"${98 - mod97(s)}%02d"

  private def mod97(s: String): Int =
    s.foldLeft("") { (acc, ch) =>
      val next = acc + ch
      if next.length > 7 then (next.toLong % 97).toString else next
    }.toInt % 97
