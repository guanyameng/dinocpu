package CODCPU

import CODCPU.simulate.{build, elfToHex}
import org.scalatest.{FlatSpec, Matchers}
import treadle.TreadleTester
import treadle.executable.TreadleException

import scala.collection.mutable

class CPUFlatSpec extends FlatSpec with Matchers

class CPUTesterDriver(cpuType: String, binary: String) {
  val optionsManager = new SimulatorOptionsManager()

  if (optionsManager.targetDirName == ".") {
    optionsManager.setTargetDirName(s"test_run_dir/$cpuType/$binary")
  }

  val hexName = s"${optionsManager.targetDirName}/${binary}.hex"

  val conf = new CPUConfig()
  conf.cpuType = cpuType
  conf.memFile = hexName

  // This compiles the chisel to firrtl
  val compiledFirrtl = build(optionsManager, conf)

  // Convert the binary to a hex file that can be loaded by treadle
  // (Do this after compiling the firrtl so the directory is created)
  val endPC = elfToHex(s"src/test/resources/risc-v/${binary}", hexName)

  // Instantiate the simulator
  val simulator = TreadleTester(compiledFirrtl, optionsManager)

  def reset(): Unit = {
    simulator.reset(5)
  }

  def initRegs(vals: Map[Int, BigInt]) {
    for ((num, value) <- vals) {
      simulator.poke(s"cpu.registers.regs_$num", value)
    }
  }

  /**
    *
    * @param vals holds "addresses" to values. Where address is the nth *word*
    */
  def initMemory(vals: Map[Int, BigInt]): Unit = {
    for ((addr, value) <- vals) {
      simulator.pokeMemory(s"cpu.mem.memory", addr, value)
    }
  }

  def checkRegs(vals: Map[Int, BigInt]): Boolean = {
    var success = true
    for ((num, value) <- vals) {
      try {
        simulator.expect(s"cpu.registers.regs_$num", value)
      } catch {
        case _: TreadleException => {
          success = false
          val real = simulator.peek(s"cpu.registers.regs_$num")
          println(s"Register $num failed to match. Was $real. Should be $value")
        }
      }
    }
    success
  }

  def checkMemory(vals: Map[Int, BigInt]): Boolean = {
    var success = true
    for ((addr, value) <- vals) {
      try {
        simulator.expectMemory("mem.memory", addr, value)
      } catch {
        case e: TreadleException => {
          success = false
          val real = simulator.peekMemory("mem.memory", addr)
          println(s"Memory at address 0x${addr.toHexString} failed to match. Was $real. Should be $value")
        }
      }
    }
    success
  }

  def run(cycles: Int): Unit = {
    var cycle = 0
    while (cycle < cycles) {
      simulator.step(1)
      cycle += 1
    }
  }
}

case class CPUTestCase(
  binary:  String,
  cycles: Int,
  initRegs: Map[Int, BigInt],
  checkRegs: Map[Int, BigInt],
  initMem: Map[Int, BigInt],
  checkMem: Map[Int, BigInt],
  extraName: String = ""
  )
{
}

object CPUTesterDriver {
  val testCases = List[CPUTestCase](
    CPUTestCase("add1",   1,  Map(5 -> 1234),
                              Map(0 -> 0, 5 -> 1234, 6 -> 1234),
                              Map(), Map()),
    CPUTestCase("add2",   1,  Map(5 -> 1234, 20 -> 5678),
                              Map(0 -> 0, 10 -> 6912),
                              Map(), Map()),
    CPUTestCase("add0",   1,  Map(5 -> 1234, 6 -> 3456),
                              Map(0 -> 0, 5 -> 1234, 6 -> 3456),
                              Map(), Map()),
    CPUTestCase("addfwd", 10, Map(5 -> 1, 10 -> 0),
                              Map(5 -> 1, 10 -> 10),
                              Map(), Map()),
    CPUTestCase("and",    1,  Map(5 -> 1234, 6 -> 5678),
                              Map(7 -> 1026),
                              Map(), Map()),
    CPUTestCase("beq",    3,  Map(5 -> 1234, 6 -> 1, 7 -> 5678, 8 -> 9012),
                              Map(5 -> 0, 6 -> 1, 7 -> 5678, 8 -> 9012),
                              Map(), Map(), "False"),
    CPUTestCase("beq",    3,  Map(5 -> 1234, 6 -> 1, 7 -> 5678, 28 -> 5678),
                              Map(5 -> 1235, 6 -> 1, 7 -> 5678, 28 -> 5678),
                              Map(), Map(), "True"),
    CPUTestCase("lw1",    1,  Map(),
                              Map(5 -> BigInt("ffffffff", 16)),
                              Map(), Map()),
    CPUTestCase("lwfwd",  2,  Map(5 -> BigInt("ffffffff", 16), 10 -> 5),
                              Map(5 -> 1, 10 -> 6),
                              Map(), Map()),
    CPUTestCase("or",     1,  Map(5 -> 1234, 6 -> 5678),
                              Map(7 -> 5886),
                              Map(), Map()),
    CPUTestCase("sub",    1,  Map(5 -> 1234, 6 -> 5678),
                              Map(7 -> BigInt("FFFFEEA4", 16)),
                              Map(), Map()),
    CPUTestCase("sw",     6,  Map(5 -> 1234),
                              Map(6 -> 1234),
                              Map(), Map(0x100 -> 1234))
  )
  def apply(testCase: CPUTestCase, cpuType: String): Boolean = {
    val driver = new CPUTesterDriver(cpuType, testCase.binary)
    driver.initRegs(testCase.initRegs)
    driver.initMemory(testCase.initMem)
    driver.run(testCase.cycles)
    val success = driver.checkRegs(testCase.checkRegs)
    success && driver.checkMemory(testCase.checkMem)
  }
}