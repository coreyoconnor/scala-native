package scala.scalanative
package nir

import scala.collection.mutable

import util.unsupported

/** Analysis that's used to answer following questions:
 *
 *  * What are the predecessors of given block?
 *
 *  * What are the successors of given block?
 */
private[scalanative] object ControlFlow {
  final case class EdgeBuilder(from: Local, to: Local, next: Next)

  final class BlockBuilder(val id: Local, val k: Int, val inst: Inst.Label, val pos: SourcePosition) {
    val inEdges = mutable.UnrolledBuffer.empty[EdgeBuilder]
    val outEdges = mutable.UnrolledBuffer.empty[EdgeBuilder]
    def instsFrom = k + 1
    // exclusive bound
    var instsUntil = k + 1
  }

  final case class Edge(from: Block, to: Block, next: Next)

  final case class Block(
      id: Local,
      params: Seq[Val.Local],
      allInsts: IndexedSeq[Inst],
      instsFrom: Int,
      instsUntil: Int,
      isEntry: Boolean
  )(implicit val pos: SourcePosition) {
    val inEdges = mutable.UnrolledBuffer.empty[Edge]
    val outEdges = mutable.UnrolledBuffer.empty[Edge]

    def insts: IndexedSeq[Inst] = allInsts.slice(instsFrom, instsUntil)

    lazy val splitCount: Int = {
      var count = 0
      insts.foreach {
        case Inst.Let(_, _: Op.Call, unwind) if unwind ne Next.None =>
          count += 1
        case _ =>
          ()
      }
      count
    }

    def pred = inEdges.map(_.from)
    def succ = outEdges.map(_.to)
    def label = Inst.Label(id, params)
    def show = id.show
  }

  final class Graph(
      val entry: Block,
      val all: Seq[Block],
      val find: mutable.Map[Local, Block]
  )

  object Graph {
    def apply(insts: IndexedSeq[Inst]): Graph = {
      assert(insts.nonEmpty)

      val locations = {
        val entries = mutable.Map.empty[Local, (Int, Inst.Label)]
        var i = 0
        insts.foreach { inst =>
          inst match {
            case inst: Inst.Label => entries(inst.id) = (i, inst)
            case _                => ()
          }

          i += 1
        }

        entries
      }

      val blockBuilds = mutable.Map.empty[Local, BlockBuilder]

      // stack of local labels of blocks to visit
      var todo = List.empty[Local]

      def edge(from: BlockBuilder, to: BlockBuilder, next: Next) = {
        val e = EdgeBuilder(from.id, to.id, next)
        from.outEdges += e
        to.inEdges += e
      }

      def block(local: Local)(implicit pos: SourcePosition): BlockBuilder =
        blockBuilds.getOrElseUpdate(
          local, {
            val (k, inst) = locations(local)
            val builder = new BlockBuilder(local, k, inst, pos)
            todo ::= local
            builder
          }
        )

      def visit(node: BlockBuilder): Unit = {
        var cf: Inst.Cf = null
        while(cf == null) {
          insts(node.instsUntil) match {
            case inst: Inst.Cf => {
              cf = inst
            }
            case inst @ Inst.Let(_, op, unwind) if unwind ne Next.None => {
              edge(node, block(unwind.id)(inst.pos), unwind)
            }
            case _ => ()
          }

          node.instsUntil += 1
        }

        implicit val pos: SourcePosition = cf.pos

        cf match {
          case _: Inst.Ret =>
            ()
          case Inst.Jump(next) =>
            edge(node, block(next.id), next)
          case Inst.If(_, next1, next2) =>
            edge(node, block(next1.id), next1)
            edge(node, block(next2.id), next2)
          case Inst.LinktimeIf(_, next1, next2) =>
            edge(node, block(next1.id), next1)
            edge(node, block(next2.id), next2)
          case Inst.Switch(_, default, cases) =>
            edge(node, block(default.id), default)
            cases.foreach { case_ => edge(node, block(case_.id), case_) }
          case Inst.Throw(_, next) =>
            if (next ne Next.None) {
              edge(node, block(next.id), next)
            }
          case Inst.Unreachable(next) =>
            if (next ne Next.None) {
              edge(node, block(next.id), next)
            }
        }
      }

      val entryInst = insts.head.asInstanceOf[Inst.Label]
      val entryBuilder = block(entryInst.id)(entryInst.pos)
      val visited = mutable.Set.empty[Local]

      while (todo.nonEmpty) {
        val id = todo.head
        todo = todo.tail
        if (!visited(id)) {
          visited += id
          val builder = blockBuilds(id)
          visit(builder)
        }
      }

      val blocks = mutable.Map.empty[Local, Block]

      val all = insts.collect {
        case Inst.Label(id, _) if visited.contains(id) => {
          val builder = blockBuilds(id)

          val block = Block(builder.id, builder.inst.params, insts, builder.instsFrom, builder.instsUntil, builder.k == 0)(builder.pos)
          blocks += id -> block
          block
        }
      }

      new Graph(blocks(entryBuilder.id), all, blocks)
    }
  }

  def removeDeadBlocks(insts: IndexedSeq[Inst]): IndexedSeq[Inst] = {
    val cfg = ControlFlow.Graph(insts)
    val buf = new nir.InstructionBuilder()(Fresh(insts))

    cfg.all.foreach { b =>
      buf += b.label
      buf ++= b.insts
    }

    buf.toSeq
  }
}
