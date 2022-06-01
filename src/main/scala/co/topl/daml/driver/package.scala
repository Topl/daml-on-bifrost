package co.topl.daml

package object driver {

  // Bifrost supports heights as `Long`, but for indexing purposes here
  // we will represent offsets as `Int`
  type Index = Int

  val StartIndex: Index = 0
}
