package co.topl.ledger

import java.util.zip.ZipInputStream

import com.daml.daml_lf_dev.DamlLf
import com.daml.ledger.test.TestDar
import com.daml.lf.archive.{Dar, DarParser}

import scala.util.Try

object TestDarReader {

  def readCommonTestDar(testDar: TestDar): Try[Dar[DamlLf.Archive]] = read(testDar.path)

  def read(path: String): Try[Dar[DamlLf.Archive]] =
    DarParser
      .readArchive(
        path,
        new ZipInputStream(this.getClass.getClassLoader.getResourceAsStream(path))
      )
      .toTry
}
