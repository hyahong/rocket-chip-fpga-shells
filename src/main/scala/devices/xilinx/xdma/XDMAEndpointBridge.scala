package sifive.fpgashells.devices.xilinx.xdma

import chisel3._
import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.devices.tilelink.{DevNullParams, TLError}
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.prci.AsynchronousCrossing
import freechips.rocketchip.subsystem.CrossesToOnlyOneClockDomain
import freechips.rocketchip.tilelink._
import freechips.rocketchip.util.{AsyncQueueParams, AsyncResetSynchronizerShiftReg, ResetCatchAndSync}
import sifive.fpgashells.ip.xilinx.xdma.XDMAEndpointBlackBox

/** Buffered reference clocks enter here; the IBUFDS_GTE4 and PERST IBUF live at the board top. */
class XDMAEndpointPads extends Bundle {
  val pci_exp_txp = Output(UInt(8.W))
  val pci_exp_txn = Output(UInt(8.W))
  val pci_exp_rxp = Input(UInt(8.W))
  val pci_exp_rxn = Input(UInt(8.W))
  val sys_clk = Input(Clock())
  val sys_clk_gt = Input(Clock())
  val sys_rst_n = Input(Bool())
}

class XDMAEndpointBridgeIO extends Bundle {
  val pcie = new XDMAEndpointPads
  val ddrCalibDone = Input(Bool())
  val chipResetObserved = Input(Bool())
  val holdChipReset = Output(Bool())
  val axiClock = Output(Clock())
  val axiReset = Output(Bool())
  val linkUp = Output(Bool())
}

/** PCIe clock island. Its parent must drive module.clock/reset from io.axiClock/axiReset.
  * The host DMA address is a full system physical address, including the DDR base.
  */
class XDMAEndpointIsland(implicit p: Parameters)
    extends LazyModule with CrossesToOnlyOneClockDomain {
  val crossing = AsynchronousCrossing(8)
  val dma = AXI4MasterNode(Seq(AXI4MasterPortParameters(Seq(
    AXI4MasterParameters(name = "host_dma", id = IdRange(0, 16))
  ))))

  // Fragment arbitrary AXI bursts before converting to aligned TileLink requests.
  // The yanker bounds outstanding transactions per compressed ID and preserves echo metadata.
  // Cut both request and ready paths after fragmentation and before FIFO ordering.
  // Two entries sustain one beat/cycle without combinational flow or ready bypass.
  private val pipelineBuffer = BufferParams(depth = 2, flow = false, pipe = false)
  val master: TLOutwardNode =
    TLFIFOFixer(TLFIFOFixer.all) :=
    TLBuffer(pipelineBuffer) :=
    TLWidthWidget(32) :=
    AXI4ToTL(wcorrupt = false) :=
    AXI4UserYanker(capMaxFlight = Some(16)) :=
    AXI4Buffer(pipelineBuffer) :=
    AXI4Fragmenter() :=
    AXI4IdIndexer(idBits = 2) :=
    AXI4Buffer() := dma

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    val io = IO(new XDMAEndpointBridgeIO)
    val blackbox = Module(new XDMAEndpointBlackBox)
    val ip = blackbox.io
    val (bus, edge) = dma.out.head
    require(edge.bundle.addrBits == 64, "Keep the 64-bit error window reachable; do not truncate host addresses")
    require(edge.bundle.dataBits == 256 && edge.bundle.idBits == 4)

    ip.sys_clk := io.pcie.sys_clk
    ip.sys_clk_gt := io.pcie.sys_clk_gt
    ip.sys_rst_n := io.pcie.sys_rst_n
    ip.pci_exp_rxp := io.pcie.pci_exp_rxp
    ip.pci_exp_rxn := io.pcie.pci_exp_rxn
    io.pcie.pci_exp_txp := ip.pci_exp_txp
    io.pcie.pci_exp_txn := ip.pci_exp_txn
    ip.usr_irq_req := 0.U
    io.axiClock := ip.axi_aclk
    io.axiReset := ResetCatchAndSync(ip.axi_aclk,
      !ip.axi_aresetn || !io.pcie.sys_rst_n, name = Some("xdma_reset_sync"))
    io.linkUp := ip.user_lnk_up

    // Explicit assignment keeps the vendor's flat port names independent of AXI Bundle naming.
    bus.aw.bits := 0.U.asTypeOf(bus.aw.bits)
    bus.aw.bits.id := ip.m_axi_awid
    bus.aw.bits.addr := ip.m_axi_awaddr
    bus.aw.bits.len := ip.m_axi_awlen
    bus.aw.bits.size := ip.m_axi_awsize
    bus.aw.bits.burst := ip.m_axi_awburst
    bus.aw.bits.lock := ip.m_axi_awlock
    bus.aw.bits.cache := ip.m_axi_awcache
    bus.aw.bits.prot := ip.m_axi_awprot
    bus.aw.valid := ip.m_axi_awvalid
    ip.m_axi_awready := bus.aw.ready

    bus.w.bits := 0.U.asTypeOf(bus.w.bits)
    bus.w.bits.data := ip.m_axi_wdata
    bus.w.bits.strb := ip.m_axi_wstrb
    bus.w.bits.last := ip.m_axi_wlast
    bus.w.valid := ip.m_axi_wvalid
    ip.m_axi_wready := bus.w.ready

    ip.m_axi_bid := bus.b.bits.id
    ip.m_axi_bresp := bus.b.bits.resp
    ip.m_axi_bvalid := bus.b.valid
    bus.b.ready := ip.m_axi_bready

    bus.ar.bits := 0.U.asTypeOf(bus.ar.bits)
    bus.ar.bits.id := ip.m_axi_arid
    bus.ar.bits.addr := ip.m_axi_araddr
    bus.ar.bits.len := ip.m_axi_arlen
    bus.ar.bits.size := ip.m_axi_arsize
    bus.ar.bits.burst := ip.m_axi_arburst
    bus.ar.bits.lock := ip.m_axi_arlock
    bus.ar.bits.cache := ip.m_axi_arcache
    bus.ar.bits.prot := ip.m_axi_arprot
    bus.ar.valid := ip.m_axi_arvalid
    ip.m_axi_arready := bus.ar.ready

    ip.m_axi_rid := bus.r.bits.id
    ip.m_axi_rdata := bus.r.bits.data
    ip.m_axi_rresp := bus.r.bits.resp
    ip.m_axi_rlast := bus.r.bits.last
    ip.m_axi_rvalid := bus.r.valid
    bus.r.ready := ip.m_axi_rready

    // Keep ChipTop in reset initially; allow the host to release it explicitly.
    val control = Module(new XDMAHostControl(allowRelease = true))
    val csr = control.io.axil
    csr.awaddr := ip.m_axil_awaddr
    csr.awvalid := ip.m_axil_awvalid
    ip.m_axil_awready := csr.awready
    csr.wdata := ip.m_axil_wdata
    csr.wstrb := ip.m_axil_wstrb
    csr.wvalid := ip.m_axil_wvalid
    ip.m_axil_wready := csr.wready
    ip.m_axil_bresp := csr.bresp
    ip.m_axil_bvalid := csr.bvalid
    csr.bready := ip.m_axil_bready
    csr.araddr := ip.m_axil_araddr
    csr.arvalid := ip.m_axil_arvalid
    ip.m_axil_arready := csr.arready
    ip.m_axil_rdata := csr.rdata
    ip.m_axil_rresp := csr.rresp
    ip.m_axil_rvalid := csr.rvalid
    csr.rready := ip.m_axil_rready

    // Single-bit status crossings. Reset is asynchronously asserted and synchronously released.
    control.io.linkUp := ip.user_lnk_up
    control.io.ddrCalibDone := AsyncResetSynchronizerShiftReg(
      io.ddrCalibDone, 2, init = 0, name = Some("ddr_calib_sync"))
    control.io.chipResetObserved := AsyncResetSynchronizerShiftReg(
      io.chipResetObserved, 2, init = 0, name = Some("chip_reset_status_sync"))
    io.holdChipReset := control.io.holdReset
  }
}

/** Shared memory fabric, clocked by the harness PLL at 250 MHz.
  * CPU -> asynchronous TL -> this fabric; XDMA -> asynchronous TL -> this fabric.
  * The existing MIG wrapper retains its own AXI crossing to the 200 MHz DDR UI.
  * DMA bypasses CPU caches and is NOT cache coherent.
  */
class XDMAEndpointDDRBridge(cpuBeatBytes: Int)(implicit p: Parameters) extends LazyModule {
  val endpoint = LazyModule(new XDMAEndpointIsland)
  val cpuSink = LazyModule(new TLAsyncCrossingSink(AsyncQueueParams(depth = 8)))
  val xbar = LazyModule(new TLXbar)
  val error = LazyModule(new TLError(DevNullParams(
    address = Seq(AddressSet((BigInt(1) << 64) - 4096, 4095)),
    maxAtomic = 0, maxTransfer = 4096, executable = false), beatBytes = 32))
  val memoryNode = TLIdentityNode()

  // A 32-byte shared bus; narrowing to MIG's 8-byte AXI port happens on the DDR branch.
  xbar.node := TLBuffer() := endpoint.crossTLOut(endpoint.master)
  xbar.node := TLWidthWidget(cpuBeatBytes) := cpuSink.node
  error.node := xbar.node
  memoryNode := TLWidthWidget(32) := xbar.node

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    val io = IO(new XDMAEndpointBridgeIO)
    io <> endpoint.module.io
    endpoint.module.clock := endpoint.module.io.axiClock
    endpoint.module.reset := endpoint.module.io.axiReset
  }
}
