package xiangshan.mem

import chisel3._
import chisel3.util._
import utils._
import xiangshan._
import xiangshan.cache._
import xiangshan.cache.{DCacheWordIO, DCacheLineIO, TlbRequestIO, MemoryOpConstants}
import xiangshan.backend.LSUOpType
import xiangshan.mem._
import xiangshan.backend.roq.RoqPtr

class LQDataEntry extends XSBundle {
  // val vaddr = UInt(VAddrBits.W)
  val paddr = UInt(PAddrBits.W)
  val mask = UInt(8.W)
  val data = UInt(XLEN.W)
  val exception = ExceptionVec()
  val fwdMask = Vec(8, Bool())
}

// Data module define
// These data modules are like SyncDataModuleTemplate, but support cam-like ops
class PaddrModule(numEntries: Int, numRead: Int, numWrite: Int) extends XSModule with HasDCacheParameters {
  val io = IO(new Bundle {
    val raddr = Input(Vec(numRead, UInt(log2Up(numEntries).W)))
    val rdata = Output(Vec(numRead, UInt((PAddrBits).W)))
    val wen   = Input(Vec(numWrite, Bool()))
    val waddr = Input(Vec(numWrite, UInt(log2Up(numEntries).W)))
    val wdata = Input(Vec(numWrite, UInt((PAddrBits).W)))
    val violationMdata = Input(Vec(2, UInt((PAddrBits).W)))
    val violationMmask = Output(Vec(2, Vec(numEntries, Bool())))
    val refillMdata = Input(UInt((PAddrBits).W))
    val refillMmask = Output(Vec(numEntries, Bool()))
  })

  val data = Reg(Vec(numEntries, UInt((PAddrBits).W)))

  // read ports
  for (i <- 0 until numRead) {
    io.rdata(i) := data(io.raddr(i))
  }

  // below is the write ports (with priorities)
  for (i <- 0 until numWrite) {
    when (io.wen(i)) {
      data(io.waddr(i)) := io.wdata(i)
    }
  }
  
  // content addressed match
  for (i <- 0 until 2) {
    for (j <- 0 until numEntries) {
      io.violationMmask(i)(j) := io.violationMdata(i)(PAddrBits-1, 3) === data(j)(PAddrBits-1, 3)
    }
  }

  for (j <- 0 until numEntries) {
    io.refillMmask(j) := get_block_addr(io.refillMdata) === get_block_addr(data(j))
  }

  // DataModuleTemplate should not be used when there're any write conflicts
  for (i <- 0 until numWrite) {
    for (j <- i+1 until numWrite) {
      assert(!(io.wen(i) && io.wen(j) && io.waddr(i) === io.waddr(j)))
    }
  }
}

class MaskModule(numEntries: Int, numRead: Int, numWrite: Int) extends XSModule {
  val io = IO(new Bundle {
    val raddr = Input(Vec(numRead, UInt(log2Up(numEntries).W)))
    val rdata = Output(Vec(numRead, UInt(8.W)))
    val wen   = Input(Vec(numWrite, Bool()))
    val waddr = Input(Vec(numWrite, UInt(log2Up(numEntries).W)))
    val wdata = Input(Vec(numWrite, UInt(8.W)))
    val violationMdata = Input(Vec(2, UInt((PAddrBits).W)))
    val violationMmask = Output(Vec(2, Vec(numEntries, Bool())))
  })

  val data = Reg(Vec(numEntries, UInt(8.W)))

  // read ports
  for (i <- 0 until numRead) {
    io.rdata(i) := data(io.raddr(i))
  }

  // below is the write ports (with priorities)
  for (i <- 0 until numWrite) {
    when (io.wen(i)) {
      data(io.waddr(i)) := io.wdata(i)
    }
  }
  
  // content addressed match
  for (i <- 0 until 2) {
    for (j <- 0 until numEntries) {
      io.violationMmask(i)(j) := (io.violationMdata(i) & data(j)).orR
    }
  }

  // DataModuleTemplate should not be used when there're any write conflicts
  for (i <- 0 until numWrite) {
    for (j <- i+1 until numWrite) {
      assert(!(io.wen(i) && io.wen(j) && io.waddr(i) === io.waddr(j)))
    }
  }
}

class CoredataModule(numEntries: Int, numRead: Int, numWrite: Int) extends XSModule with HasDCacheParameters {
  val io = IO(new Bundle {
    // data io
    // read
    val raddr = Input(Vec(numRead, UInt(log2Up(numEntries).W)))
    val rdata = Output(Vec(numRead, UInt(XLEN.W)))
    // address indexed write
    val wen   = Input(Vec(numWrite, Bool()))
    val waddr = Input(Vec(numWrite, UInt(log2Up(numEntries).W)))
    val wdata = Input(Vec(numWrite, UInt(XLEN.W)))
    // masked write
    val mwmask = Input(Vec(numEntries, Bool()))
    val refillData = Input(UInt((cfg.blockBytes * 8).W))
    
    // fwdMask io
    val fwdMaskWdata = Input(Vec(numWrite, UInt(8.W)))
    val fwdMaskWen = Input(Vec(numWrite, Bool()))
    // fwdMaskWaddr = waddr

    // paddr io
    // 3 bits in paddr need to be stored in CoredataModule for refilling
    val paddrWdata = Input(Vec(numWrite, UInt((PAddrBits).W)))
    val paddrWen = Input(Vec(numWrite, Bool()))
  })

  val data = Reg(Vec(numEntries, UInt(XLEN.W)))
  val fwdMask = Reg(Vec(numEntries, UInt(8.W)))
  val wordIndex = Reg(Vec(numEntries, UInt((blockOffBits - wordOffBits).W)))

  // read ports
  for (i <- 0 until numRead) {
    io.rdata(i) := data(io.raddr(i))
  }

  // below is the write ports (with priorities)
  for (i <- 0 until numWrite) {
    when (io.wen(i)) {
      data(io.waddr(i)) := io.wdata(i)
    }
    when (io.fwdMaskWen(i)) {
      fwdMask(io.waddr(i)) := io.fwdMaskWdata(i)
    }
    when (io.paddrWen(i)) {
      wordIndex(io.waddr(i)) := get_word(io.paddrWdata(i))
    }
  }


  // masked write
  // refill missed load
  def mergeRefillData(refill: UInt, fwd: UInt, fwdMask: UInt): UInt = {
    val res = Wire(Vec(8, UInt(8.W)))
    (0 until 8).foreach(i => {
      res(i) := Mux(fwdMask(i), fwd(8 * (i + 1) - 1, 8 * i), refill(8 * (i + 1) - 1, 8 * i))
    })
    res.asUInt
  }

  // split dcache result into words
  val words = VecInit((0 until blockWords) map { i => io.refillData(DataBits * (i + 1) - 1, DataBits * i)})

  // refill data according to matchMask, refillMask and refill.vald
  for (j <- 0 until numEntries) {
    when (io.mwmask(j)) {
      val refillData = words(wordIndex(j)) // TODO
      data(j) := mergeRefillData(refillData, data(j), fwdMask(j))
    }
  }

  // DataModuleTemplate should not be used when there're any write conflicts
  for (i <- 0 until numWrite) {
    for (j <- i+1 until numWrite) {
      assert(!(io.wen(i) && io.wen(j) && io.waddr(i) === io.waddr(j)))
    }
  }
}

class LoadQueueData(size: Int, wbNumRead: Int, wbNumWrite: Int) extends XSModule with HasDCacheParameters with HasCircularQueuePtrHelper {
  val io = IO(new Bundle() {
    val wb = new Bundle() {
      val wen = Vec(wbNumWrite, Input(Bool()))
      val waddr = Input(Vec(wbNumWrite, UInt(log2Up(size).W)))
      val wdata = Input(Vec(wbNumWrite, new LQDataEntry))
      val raddr = Input(Vec(wbNumRead, UInt(log2Up(size).W)))
      val rdata = Output(Vec(wbNumRead, new LQDataEntry))
    }
    val uncache = new Bundle() {
      val wen = Input(Bool())
      val waddr = Input(UInt(log2Up(size).W))
      val wdata = Input(UInt(XLEN.W)) // only write back uncache data
      val raddr = Input(UInt(log2Up(size).W))
      val rdata = Output(new LQDataEntry)
    }
    val refill = new Bundle() {
      val valid = Input(Bool())
      val paddr = Input(UInt(PAddrBits.W))
      val data = Input(UInt((cfg.blockBytes * 8).W))
      val refillMask = Input(Vec(size, Bool()))
      val matchMask = Output(Vec(size, Bool()))
    }
    val violation = Vec(StorePipelineWidth, new Bundle() {
      val paddr = Input(UInt(PAddrBits.W))
      val mask = Input(UInt(8.W))
      val violationMask = Output(Vec(size, Bool()))
    })
    val debug = Output(Vec(size, new LQDataEntry))

    def wbWrite(channel: Int, waddr: UInt, wdata: LQDataEntry): Unit = {
      require(channel < wbNumWrite && wbNumWrite >= 0)
      // need extra "this.wb(channel).wen := true.B"
      this.wb.waddr(channel) := waddr
      this.wb.wdata(channel) := wdata
    }

    def uncacheWrite(waddr: UInt, wdata: UInt): Unit = {
      // need extra "this.uncache.wen := true.B"
      this.uncache.waddr := waddr
      this.uncache.wdata := wdata
    }

    // def refillWrite(ldIdx: Int): Unit = {
    // }
    // use "this.refill.wen(ldIdx) := true.B" instead
  })

  // val data = Reg(Vec(size, new LQDataEntry))
  // data module
  val paddrModule = Module(new PaddrModule(size, numRead = 3, numWrite = 2))
  val maskModule = Module(new MaskModule(size, numRead = 3, numWrite = 2))
  val exceptionModule = Module(new AsyncDataModuleTemplate(ExceptionVec(), size, numRead = 3, numWrite = 2))
  val coredataModule = Module(new CoredataModule(size, numRead = 3, numWrite = 3))

  // read data
  // read port 0 -> wbNumRead-1
  (0 until wbNumRead).map(i => {
    paddrModule.io.raddr(i) := io.wb.raddr(i)
    maskModule.io.raddr(i) := io.wb.raddr(i)
    exceptionModule.io.raddr(i) := io.wb.raddr(i)
    coredataModule.io.raddr(i) := io.wb.raddr(i)

    io.wb.rdata(i).paddr := paddrModule.io.rdata(i)
    io.wb.rdata(i).mask := maskModule.io.rdata(i)
    io.wb.rdata(i).data := coredataModule.io.rdata(i)
    io.wb.rdata(i).exception := exceptionModule.io.rdata(i)
    io.wb.rdata(i).fwdMask := DontCare
  })
  
  // read port wbNumRead
  paddrModule.io.raddr(wbNumRead) := io.uncache.raddr
  maskModule.io.raddr(wbNumRead) := io.uncache.raddr
  exceptionModule.io.raddr(wbNumRead) := io.uncache.raddr
  coredataModule.io.raddr(wbNumRead) := io.uncache.raddr

  io.uncache.rdata.paddr := paddrModule.io.rdata(wbNumRead)
  io.uncache.rdata.mask := maskModule.io.rdata(wbNumRead)
  io.uncache.rdata.data := coredataModule.io.rdata(wbNumRead)
  io.uncache.rdata.exception := exceptionModule.io.rdata(wbNumRead)
  io.uncache.rdata.fwdMask := DontCare
  
  // write data
  // write port 0 -> wbNumWrite-1
  (0 until wbNumWrite).map(i => {
    paddrModule.io.wen(i) := false.B
    maskModule.io.wen(i) := false.B
    exceptionModule.io.wen(i) := false.B
    coredataModule.io.wen(i) := false.B
    coredataModule.io.fwdMaskWen(i) := false.B
    coredataModule.io.paddrWen(i) := false.B

    paddrModule.io.waddr(i) := io.wb.waddr(i)
    maskModule.io.waddr(i) := io.wb.waddr(i)
    exceptionModule.io.waddr(i) := io.wb.waddr(i)
    coredataModule.io.waddr(i) := io.wb.waddr(i)

    paddrModule.io.wdata(i) := io.wb.wdata(i).paddr
    maskModule.io.wdata(i) := io.wb.wdata(i).mask
    exceptionModule.io.wdata(i) := io.wb.wdata(i).exception
    coredataModule.io.wdata(i) := io.wb.wdata(i).data
    coredataModule.io.fwdMaskWdata(i) := io.wb.wdata(i).fwdMask.asUInt
    coredataModule.io.paddrWdata(i) := io.wb.wdata(i).paddr

    when(io.wb.wen(i)){
      paddrModule.io.wen(i) := true.B
      maskModule.io.wen(i) := true.B
      exceptionModule.io.wen(i) := true.B
      coredataModule.io.wen(i) := true.B
      coredataModule.io.fwdMaskWen(i) := true.B
      coredataModule.io.paddrWen(i) := true.B
    }
  })
  
  // write port wbNumWrite
  // exceptionModule.io.wen(wbNumWrite) := false.B
  coredataModule.io.wen(wbNumWrite) := io.uncache.wen
  coredataModule.io.fwdMaskWen(wbNumWrite) := false.B
  coredataModule.io.paddrWen(wbNumWrite) := false.B

  coredataModule.io.waddr(wbNumWrite) := io.uncache.waddr

  coredataModule.io.fwdMaskWdata(wbNumWrite) := DontCare
  coredataModule.io.paddrWdata(wbNumWrite) := DontCare
  coredataModule.io.wdata(wbNumWrite) := io.uncache.wdata

  // mem access violation check, gen violationMask
  (0 until StorePipelineWidth).map(i => {
    paddrModule.io.violationMdata(i) := io.violation(i).paddr
    maskModule.io.violationMdata(i) := io.violation(i).mask
    io.violation(i).violationMask := (paddrModule.io.violationMmask(i).asUInt & maskModule.io.violationMmask(i).asUInt).asBools
    // VecInit((0 until size).map(j => {
      // val addrMatch = io.violation(i).paddr(PAddrBits - 1, 3) === data(j).paddr(PAddrBits - 1, 3)
      // val violationVec = (0 until 8).map(k => data(j).mask(k) && io.violation(i).mask(k))
      // Cat(violationVec).orR() && addrMatch
    // }))
  })
  
  // refill missed load
  def mergeRefillData(refill: UInt, fwd: UInt, fwdMask: UInt): UInt = {
    val res = Wire(Vec(8, UInt(8.W)))
    (0 until 8).foreach(i => {
      res(i) := Mux(fwdMask(i), fwd(8 * (i + 1) - 1, 8 * i), refill(8 * (i + 1) - 1, 8 * i))
    })
    res.asUInt
  }

  // gen paddr match mask
  paddrModule.io.refillMdata := io.refill.paddr
  (0 until size).map(i => {
    io.refill.matchMask := paddrModule.io.refillMmask
    // io.refill.matchMask(i) := get_block_addr(data(i).paddr) === get_block_addr(io.refill.paddr)
  })
  
  // refill data according to matchMask, refillMask and refill.valid
  coredataModule.io.refillData := io.refill.data
  (0 until size).map(i => {
    coredataModule.io.mwmask(i) := io.refill.valid && io.refill.matchMask(i) && io.refill.refillMask(i)
  })

  // debug data read
  io.debug := DontCare
}
