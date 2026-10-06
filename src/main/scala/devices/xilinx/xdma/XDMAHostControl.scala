package sifive.fpgashells.devices.xilinx.xdma

import chisel3._
import chisel3.util._

/** AXI4-Lite signals, with directions as seen by this slave.
  *
  * Protection attributes are unused by this register bank, so AWPROT/ARPROT
  * need not be connected. Data width is fixed at 32 bits for XDMA m_axil.
  */
class XDMAAXILiteSlaveIO(val addressBits: Int = 32) extends Bundle {
  val awaddr  = Input(UInt(addressBits.W))
  val awvalid = Input(Bool())
  val awready = Output(Bool())

  val wdata   = Input(UInt(32.W))
  val wstrb   = Input(UInt(4.W))
  val wvalid  = Input(Bool())
  val wready  = Output(Bool())

  val bresp   = Output(UInt(2.W))
  val bvalid  = Output(Bool())
  val bready  = Input(Bool())

  val araddr  = Input(UInt(addressBits.W))
  val arvalid = Input(Bool())
  val arready = Output(Bool())

  val rdata   = Output(UInt(32.W))
  val rresp   = Output(UInt(2.W))
  val rvalid  = Output(Bool())
  val rready  = Input(Bool())
}

/** Host-visible control registers for the VCU118 PCIe/DDR bring-up.
  *
  * Offsets from baseAddress (all accesses must be 32-bit aligned):
  *   0x00 MAGIC   RO: 0x58444d41
  *   0x04 VERSION RO: 1
  *   0x08 CONTROL RW: bit 0 requests ChipTop reset; reset value is 1
  *   0x0c STATUS  RO: bit 0 linkUp, bit 1 ddrCalibDone,
  *                    bit 2 chipResetObserved, bit 3 releaseSupported;
  *                    remaining bits are zero
  *   0x10 SCRATCH RW: 32-bit scratch register; reset value is zero
  *
  * Writes respect WSTRB. CONTROL bits other than bit 0 are ignored and
  * read as zero. Unknown/unaligned addresses and writes to RO registers
  * return SLVERR; invalid reads return zero data. With allowRelease=false,
  * a byte-enabled write clearing CONTROL bit 0 also returns SLVERR and
  * leaves reset asserted. A masked-off write is an acknowledged no-op.
  * The full address is
  * decoded: high address bits do not create aliases of these registers.
  *
  * This module runs in the XDMA m_axil clock/reset domain. Status inputs
  * must already be synchronized to this clock. holdReset is a request in
  * this domain; the integrator must perform safe reset-domain crossing.
  * Leave allowRelease=false for the initial DDR-only milestone. Enable it
  * only after the boot/reset path supports executing the loaded program.
  * Do not reset PCIe, DDR, or their interconnect with holdReset.
  *
  * Validation cases: AW before W, W before AW, simultaneous AW/W, delayed
  * BREADY/RREADY, every byte strobe, RO/unknown/unaligned/high-bit accesses,
  * and reset during a partial transaction. A simultaneous read and write
  * of a register observes the value before that write.
  */
class XDMAHostControl(
    val addressBits: Int = 32,
    val baseAddress: BigInt = 0,
    val allowRelease: Boolean = false
) extends Module {
  require(addressBits >= 5, "The CSR address bus must cover all registers")
  require(baseAddress >= 0 && (baseAddress & 3) == 0,
    "The CSR base address must be nonnegative and 32-bit aligned")
  require(baseAddress + 0x14 <= (BigInt(1) << addressBits),
    "The CSR register bank must fit in the address bus")

  val io = IO(new Bundle {
    val axil              = new XDMAAXILiteSlaveIO(addressBits)
    val linkUp            = Input(Bool())
    val ddrCalibDone       = Input(Bool())
    val chipResetObserved = Input(Bool())
    val holdReset         = Output(Bool())
  })

  private def address(offset: Int): UInt =
    (baseAddress + offset).U(addressBits.W)

  private val okay   = 0.U(2.W)
  private val slverr = 2.U(2.W)

  private val holdResetReg = RegInit(true.B)
  private val scratchReg   = RegInit(0.U(32.W))
  io.holdReset := holdResetReg

  // AW and W may arrive in either order, so retain them independently.
  private val awPending = RegInit(false.B)
  private val awAddress = RegInit(0.U(addressBits.W))
  private val wPending  = RegInit(false.B)
  private val wData     = RegInit(0.U(32.W))
  private val wStrobes  = RegInit(0.U(4.W))
  private val bValid    = RegInit(false.B)
  private val bResp     = RegInit(okay)

  io.axil.awready := !awPending && !bValid
  io.axil.wready  := !wPending && !bValid
  io.axil.bvalid  := bValid
  io.axil.bresp   := bResp

  when(io.axil.awvalid && io.axil.awready) {
    awPending := true.B
    awAddress := io.axil.awaddr
  }
  when(io.axil.wvalid && io.axil.wready) {
    wPending := true.B
    wData    := io.axil.wdata
    wStrobes := io.axil.wstrb
  }

  private val writeMask = Cat((0 until 4).reverse.map(i => Fill(8, wStrobes(i))))

  // Commit once both buffered halves are present. No subsequent write is
  // accepted until the corresponding B response has been consumed.
  when(awPending && wPending && !bValid) {
    awPending := false.B
    wPending  := false.B
    bValid    := true.B
    bResp     := slverr

    switch(awAddress) {
      is(address(0x08)) {
        bResp := okay
        when(wStrobes(0)) {
          if (allowRelease) {
            holdResetReg := wData(0)
          } else {
            when(!wData(0)) {
              bResp := slverr
            }
          }
        }
      }
      is(address(0x10)) {
        bResp := okay
        scratchReg := (scratchReg & ~writeMask) | (wData & writeMask)
      }
    }
  }
  when(bValid && io.axil.bready) {
    bValid := false.B
  }

  // Snapshot read data at AR acceptance and retain it under backpressure.
  private val rValid = RegInit(false.B)
  private val rData  = RegInit(0.U(32.W))
  private val rResp  = RegInit(okay)

  io.axil.arready := !rValid
  io.axil.rvalid  := rValid
  io.axil.rdata   := rData
  io.axil.rresp   := rResp

  when(io.axil.arvalid && io.axil.arready) {
    rValid := true.B
    rData  := 0.U
    rResp  := slverr

    switch(io.axil.araddr) {
      is(address(0x00)) {
        rData := "h58444d41".U
        rResp := okay
      }
      is(address(0x04)) {
        rData := 1.U
        rResp := okay
      }
      is(address(0x08)) {
        rData := holdResetReg.asUInt
        rResp := okay
      }
      is(address(0x0c)) {
        rData := Cat(0.U(28.W), allowRelease.B,
          io.chipResetObserved, io.ddrCalibDone, io.linkUp)
        rResp := okay
      }
      is(address(0x10)) {
        rData := scratchReg
        rResp := okay
      }
    }
  }
  when(rValid && io.axil.rready) {
    rValid := false.B
  }
}
