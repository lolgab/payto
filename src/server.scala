//> using scala 3.8.3
//> using dep org.typelevel::cats-effect::3.7.0
//> using dep org.http4s::http4s-ember-server::0.23.34
//> using dep org.http4s::http4s-dsl::0.23.34
//> using dep com.armanbilge::porcupine::0.0.1
//> using dep org.xerial:sqlite-jdbc:3.53.1.0

import cats.effect.*
import cats.syntax.all.*
import com.comcast.ip4s.*
import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.headers.Location
import org.http4s.headers.`Content-Type`
import org.http4s.implicits.uri
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.staticcontent.*
import fs2.io.file.Path as FsPath
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

  private val sellerIban = "DE75512108001245126199"

  private val maxTransferIdToSeller =
    sql"select coalesce(max(id), 0) from transfers where to_iban = $text".query(integer)

  private val findPaymentAfter =
    sql"""select t.id, t.from_iban, t.amount, coalesce(a.name, '')
          from transfers t
          left join accounts a on a.iban = t.from_iban
          where t.to_iban = $text and t.id > $integer and t.amount = $real
          order by t.id asc limit 1"""
      .query(integer *: text *: real *: text *: nil)

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
      db.option(selectByIban, sellerIban).flatMap {
        case Some(_) =>
          db.execute(sql"update accounts set name = $text where iban = $text".command, ("Negozio Demo", sellerIban))
        case None =>
          db.execute(insertAccount, ("Negozio Demo", "DE75512108001245126199", 0.0, "EUR"))
      }

  private def startServer(db: Db): IO[Unit] =
    val api = HttpRoutes.of[IO] {
      case GET -> Root / "api" / "seller" =>
        loadByIban(db, sellerIban).flatMap {
          case Some(acc) => jsonOk(accJson(acc))
          case None      => NotFound(jsonErr("Conto venditore non trovato"))
        }

      case GET -> Root / "api" / "seller" / "baseline" =>
        db.execute(maxTransferIdToSeller, sellerIban).flatMap {
          case id :: Nil => Ok(s"""{"lastId":$id}""")
          case _         => InternalServerError(jsonErr("Impossibile leggere lo stato dei pagamenti"))
        }

      case req @ GET -> Root / "api" / "seller" / "payment" =>
        sellerPayment(db, req)

      case req @ GET -> Root / "api" / "me" =>
        me(db, req)

      case req @ GET -> Root / "api" / "account" =>
        req.params.get("iban") match
          case Some(iban) => lookup(db, iban)
          case None       => BadRequest(jsonErr("Parametro IBAN mancante"))

      case req @ POST -> Root / "api" / "pay" =>
        pay(db, req)
    }

    val pages = HttpRoutes.of[IO] {
      case GET -> Root / "seller" =>
        serveWebPage("seller.html")
      case GET -> Root / "seller.html" =>
        MovedPermanently(Location(uri"/seller"))
      case GET -> Root / "bonifico" =>
        serveWebPage("bonifico.html")
      case GET -> Root / "bonifico.html" =>
        MovedPermanently(Location(uri"/bonifico"))
      case GET -> Root / "epc" =>
        MovedPermanently(Location(uri"/bonifico"))
      case GET -> Root / "epc.html" =>
        MovedPermanently(Location(uri"/bonifico"))
      case GET -> Root / ".well-known" / "assetlinks.json" =>
        serveAssetLinks
    }

    val static = fileService[IO](FileService.Config("web", ""))
    val app = (api <+> pages <+> static).orNotFound

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
    val name = s"Utente ${Random.between(1000, 9999)}"
    val iban = Iban.generate()
    db.execute(insertAccount, (name, iban, 4250.0, "EUR")) >>
      db.execute(sql"select last_insert_rowid()".query(integer)).flatMap {
        case id :: Nil =>
          loadAccount(db, id).flatMap {
            case Some(acc) => jsonOk(accJson(acc)).map(_.addCookie(cookie(id)))
            case None      => InternalServerError("Impossibile creare il conto")
          }
        case _ => InternalServerError("Impossibile creare il conto")
      }

  private def lookup(db: Db, iban: String): IO[Response[IO]] =
    val normalized = iban.replace(" ", "").toUpperCase
    db.option(selectByIban, normalized).flatMap {
      case Some((_, name, ib, _, _)) => Ok(jsonStr("name", name, "iban", ib))
      case None                      => NotFound(jsonErr(s"IBAN sconosciuto: $normalized"))
    }

  private def sellerPayment(db: Db, req: Request[IO]): IO[Response[IO]] =
    val after = req.params.get("after").flatMap(_.toLongOption).getOrElse(0L)
    req.params.get("amount").flatMap(_.toDoubleOption) match
      case None => BadRequest(jsonErr("Parametro importo mancante"))
      case Some(amt) =>
        db.option(findPaymentAfter, (sellerIban, after, amt)).flatMap {
          case Some((id, from, paid, name)) =>
            Ok(s"""{"paid":true,"id":$id,"fromIban":"$from","fromName":"${esc(name)}","amount":$paid}""")
          case None => Ok("""{"paid":false}""")
        }

  private def pay(db: Db, req: Request[IO]): IO[Response[IO]] =
    val fromId = req.cookies.find(_.name == "account_id").flatMap(_.content.toLongOption)
    if fromId.isEmpty then BadRequest(jsonErr("Nessun conto — ricarica la pagina"))
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
              case _                    => BadRequest(jsonErr("IBAN destinatario sconosciuto"))
            }
          case _ => BadRequest(jsonErr("IBAN destinatario o importo mancante"))
      }

  private def transfer(db: Db, from: Account, to: Account, amount: Double, message: String): IO[Response[IO]] =
    if amount <= 0 then BadRequest(jsonErr("L'importo deve essere positivo"))
    else if from.iban == to.iban then BadRequest(jsonErr("Non puoi pagare te stesso"))
    else if from.balance < amount then BadRequest(jsonErr("Fondi insufficienti"))
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

  private def serveWebPage(name: String): IO[Response[IO]] =
    StaticFile.fromPath(FsPath(s"web/$name")).getOrElseF(NotFound())

  private def serveAssetLinks: IO[Response[IO]] =
    StaticFile
      .fromPath(FsPath("web/.well-known/assetlinks.json"))
      .map(_.withContentType(`Content-Type`(MediaType.application.json)))
      .getOrElseF(NotFound())

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

  def generate(country: String = "IT"): String =
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
