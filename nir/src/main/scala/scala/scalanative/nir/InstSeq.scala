package scala.scalanative
package nir

object InstSeq {
  type BasicBlock = Seq[Inst]
}

case class InstSeq() {
  def labelLocations: Map[Local, (Int, Inst.Label)] = ???
      // val locations = {
      //   val entries = mutable.Map.empty[Local, (Int, Inst.Label)]
      //   var i = 0
      //   insts.foreach { inst =>
      //     inst match {
      //       case inst: Inst.Label => entries(inst.id) = (i, inst)
      //       case _                => ()
      //     }
      //
      //     i += 1
      //   }
      //
      //   entries
      // }

  def sliceAfter(label: Local): (Int, Local, Seq[Val.Local], Seq[Inst]) = ???
            // val (k, Inst.Label(n, params)) = locations(local)
            //
            // // copy all instruction up until and including
            // // first control-flow instruction after the label
            // val firstInst = k + 1
            // val body = insts.slice(
            //   firstInst,
            //   insts.indexWhere(_.isInstanceOf[Inst.Cf], from = firstInst) + 1
            // )

  def labelsIn(ids: Iterable[Local]): Seq[Local] = ???
      // val all = insts.collect {
      //   case Inst.Label(id, _) if visited.contains(id) =>
      //     blocks(id)
      // }

  def entryLabel: Inst.Label = ???
   //   insts.head.asInstanceOf[Inst.Label]


  def fresh: Fresh = ???
  // Fresh(insts)
}
