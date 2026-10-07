# See LICENSE for license details.

# Keep PCIe receive-status logic near the eight fixed GT lanes in SLR1.
if {$top eq "VCU118FPGATestHarness" && [llength [get_cells -quiet {bridge/endpoint}]]} {
  set pcie_gt [get_cells -quiet {bridge/endpoint/blackbox/inst/pcie4_ip_i/inst/xdma_vcu118_ep_pcie4_ip_gt_top_i}]
  if {[llength $pcie_gt] != 1} {error "Cannot identify PCIe GT wrapper for SLR placement"}
  set_property USER_SLR_ASSIGNMENT SLR1 $pcie_gt
  puts "PCIe GT wrapper soft SLR assignment: [get_property USER_SLR_ASSIGNMENT $pcie_gt]"
}

# Place the current design
place_design -directive Explore

# Optimize the current placed netlist
phys_opt_design -directive Explore

# Optimize dynamic power using intelligent clock gating
power_opt_design

# Checkpoint the current design
write_checkpoint -force [file join $wrkdir post_place]
