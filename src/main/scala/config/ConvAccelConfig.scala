package chipyard

import freechips.rocketchip.config.{Config, Parameters}
import freechips.rocketchip.tile.{BuildRoCC, OpcodeSet}
import freechips.rocketchip.diplomacy.LazyModule

import matrix.ConvAccelRoCC

class WithConvAccel extends Config((site, here, up) => {
  case BuildRoCC =>
    up(BuildRoCC) ++ Seq(
      (p: Parameters) => {
        val accel = LazyModule(new ConvAccelRoCC(OpcodeSet.custom0)(p))
        accel
      }
    )
})

class ConvAccelConfig extends Config(
  new WithConvAccel ++
  new RocketConfig
)
