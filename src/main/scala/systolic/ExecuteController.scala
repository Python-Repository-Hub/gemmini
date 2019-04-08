package systolic

import chisel3._
import chisel3.util._
import SystolicISA._
import Util._
import freechips.rocketchip.config.Parameters

// TODO handle reads from the same bank
// TODO handle reads from addresses that haven't been written yet
class ExecuteController[T <: Data: Arithmetic](xLen: Int, config: SystolicArrayConfig, spaddr: SPAddr,
                                               inputType: T, outputType: T, accType: T)
                                              (implicit p: Parameters) extends Module {
  import config._

  val io = IO(new Bundle {
    val cmd = Flipped(Decoupled(new SystolicCmdWithDeps))

    val read  = Vec(sp_banks, new ScratchpadReadIO(sp_bank_entries, sp_width))
    val write = Vec(sp_banks, new ScratchpadWriteIO(sp_bank_entries, sp_width))

    // TODO what's a better way to express no bits?
    val pushLoad = Decoupled(UInt(1.W))
    val pullLoad = Flipped(Decoupled(UInt(1.W)))
    val pushStore = Decoupled(UInt(1.W))
    val pullStore = Flipped(Decoupled(UInt(1.W)))

    val pushLoadLeft = Input(UInt(log2Ceil(depq_len+1).W))
    val pushStoreLeft = Input(UInt(log2Ceil(depq_len+1).W))
  })

  val block_size = meshRows*tileRows
  assert(ex_queue_length >= 6)

  val tagWidth = 32
  val tag_garbage = Cat(Seq.fill(tagWidth)(1.U(1.W)))
  val tag_with_deps = new Bundle {
    val pushLoad = Bool()
    val pushStore = Bool()
    val tag = UInt(tagWidth.W)
  }

  val cmd_q_heads = 2
  val (cmd, _) = MultiHeadedQueue(io.cmd, ex_queue_length, cmd_q_heads)
  cmd.pop := 0.U

  val current_dataflow = RegInit(Dataflow.OS.id.U)

  val functs = cmd.bits.map(_.cmd.inst.funct)
  val rs1s = VecInit(cmd.bits.map(_.cmd.rs1))
  val rs2s = VecInit(cmd.bits.map(_.cmd.rs2))

  val DoSetMode = functs(0) === MODE_CMD
  val DoComputes = functs.map(f => f === COMPUTE_AND_FLIP_CMD || f === COMPUTE_AND_STAY_CMD)
  val DoPreloads = functs.map(_ === PRELOAD_CMD)

  val preload_cmd_place = Mux(DoPreloads(0), 0.U, 1.U)

  val in_s = functs(0) === COMPUTE_AND_FLIP_CMD
  val in_s_flush = Reg(Bool())
  when (current_dataflow === Dataflow.WS.id.U) {
    in_s_flush := 0.U
  }

  val in_shift = RegInit(0.U((accType.getWidth - outputType.getWidth).W))

  // SRAM addresses of matmul operands
  val a_address_rs1 = WireInit(rs1s(0).asTypeOf(spaddr))
  val b_address_rs2 = WireInit(rs2s(0).asTypeOf(spaddr))
  val d_address_rs1 = WireInit(rs1s(preload_cmd_place).asTypeOf(spaddr))
  val c_address_rs2 = WireInit(rs2s(preload_cmd_place).asTypeOf(spaddr))

  val preload_zeros = WireInit(d_address_rs1.asUInt()(tagWidth-1, 0) === tag_garbage)
  val accumulate_zeros = WireInit(b_address_rs2.asUInt()(tagWidth-1, 0) === tag_garbage)

  // Dependency stuff
  val pushLoads = cmd.bits.map(_.deps.pushLoad)
  val pullLoads = cmd.bits.map(_.deps.pullLoad)
  val pushStores = cmd.bits.map(_.deps.pushStore)
  val pullStores = cmd.bits.map(_.deps.pullStore)
  val pushDeps = (pushLoads zip pushStores).map { case (pl, ps) => pl || ps }
  val pullDeps = (pullLoads zip pullStores).map { case (pl, ps) => pl || ps }

  val pull_deps_ready = (pullDeps, pullLoads, pullStores).zipped.map { case (pullDep, pullLoad, pullStore) =>
    !pullDep || (pullLoad && !pullStore && io.pullLoad.valid) ||
      (pullStore && !pullLoad && io.pullStore.valid) ||
      (pullLoad && pullStore && io.pullLoad.valid && io.pullStore.valid)
  }

  val push_deps_ready = (pushDeps, pushLoads, pushStores).zipped.map { case (pushDep, pushLoad, pushStore) =>
    !pushDep || (pushLoad && !pushStore && io.pushLoadLeft >= 3.U) ||
      (pushStore && !pushLoad && io.pushStoreLeft >= 3.U) ||
      (pushStore && pushLoad && io.pushLoadLeft >= 3.U && io.pushStoreLeft >= 3.U)
  }

  io.pushLoad.valid := false.B
  io.pushLoad.bits := DontCare
  io.pushStore.valid := false.B
  io.pushStore.bits := DontCare

  io.pullLoad.ready := false.B
  io.pullStore.ready := false.B

  // Instantiate the actual mesh
  val mesh = Module(new MeshWithMemory(inputType, outputType, accType, tag_with_deps, Dataflow.BOTH, tileRows,
    tileColumns, meshRows, meshColumns, shifter_banks, shifter_banks))

  mesh.io.a.valid := false.B
  mesh.io.b.valid := false.B
  mesh.io.d.valid := false.B
  mesh.io.tag_in.valid := false.B
  mesh.io.tag_garbage.pushLoad := false.B
  mesh.io.tag_garbage.pushStore := false.B
  mesh.io.tag_garbage.tag := tag_garbage
  mesh.io.flush.valid := false.B

  mesh.io.a.bits := DontCare
  mesh.io.b.bits := DontCare
  mesh.io.d.bits := DontCare
  mesh.io.tag_in.bits := DontCare
  mesh.io.s := in_s
  mesh.io.m := current_dataflow
  mesh.io.shift := in_shift

  // STATE defines
  val waiting_for_cmd :: compute :: flush :: flushing :: Nil = Enum(4)
  val control_state = RegInit(waiting_for_cmd)

  // SRAM scratchpad
  val a_read_bank_number = a_address_rs1.spbank
  val b_read_bank_number = b_address_rs2.spbank
  val d_read_bank_number = d_address_rs1.spbank

  val start_inputting_ab = WireInit(false.B)
  val start_inputting_d = WireInit(false.B)
  val start_array_outputting = WireInit(false.B)

  val perform_single_preload = RegInit(false.B)
  val perform_single_mul = RegInit(false.B)
  val perform_mul_pre = RegInit(false.B)

  val output_counter = new Counter(block_size)
  val fire_counter = Reg(UInt((log2Ceil(block_size) max 1).W))
  val fire_count_started = RegInit(false.B)
  val fired_all_rows = fire_counter === 0.U && fire_count_started
  val about_to_fire_all_rows = fire_counter === (block_size-1).U && mesh.io.a.ready && mesh.io.b.ready && mesh.io.d.ready // TODO change when square requirement lifted

  fire_counter := 0.U
  when (mesh.io.a.ready && mesh.io.b.ready && mesh.io.d.ready &&
    (start_inputting_ab || start_inputting_d)) {
    fire_counter := wrappingAdd(fire_counter, 1.U, block_size)
  }

  // Scratchpad reads
  for(i <- 0 until sp_banks){
    io.read(i).en :=
      ((a_read_bank_number === i.U && start_inputting_ab) ||
        (b_read_bank_number === i.U && start_inputting_ab && !accumulate_zeros) ||
        (d_read_bank_number === i.U && start_inputting_d && !preload_zeros))
    io.read(i).addr := MuxCase(a_address_rs1.sprow + fire_counter,
      Seq(
        (b_read_bank_number === i.U && start_inputting_ab) -> (b_address_rs2.sprow + fire_counter),
        (d_read_bank_number === i.U && start_inputting_d) -> (d_address_rs1.sprow + block_size.U - 1.U - fire_counter)
      )
    )
  }

  val readData = VecInit(io.read.map(_.data))

  val dataAbank = WireInit(a_read_bank_number)
  val dataBbank = WireInit(b_read_bank_number)
  val dataDbank = WireInit(d_read_bank_number)

  val dataA = readData(RegNext(dataAbank))
  val dataB = Mux(RegNext(accumulate_zeros), 0.U, readData(RegNext(dataBbank)))
  val dataD = Mux(RegNext(preload_zeros), 0.U, readData(RegNext(dataDbank)))

  // FSM logic
  switch (control_state) {
    is (waiting_for_cmd) {
      // Default state
      perform_single_preload := false.B
      perform_mul_pre := false.B
      perform_single_mul := false.B
      fire_count_started := false.B

      when(cmd.valid(0) && pull_deps_ready(0) && push_deps_ready(0))
      {
        when(DoSetMode) {
          val data_mode = rs1s(0)(0)
          current_dataflow := data_mode
          in_shift := rs2s(0)

          io.pullLoad.ready := cmd.bits(0).deps.pullLoad
          io.pullStore.ready := cmd.bits(0).deps.pullStore
          // TODO add support for pushing dependencies on the set-mode command
          assert(!pushDeps(0))

          cmd.pop := 1.U
        }

        // Preload
        .elsewhen(DoPreloads(0)) {
          perform_single_preload := true.B
          start_inputting_d := true.B

          io.pullLoad.ready := cmd.bits(0).deps.pullLoad
          io.pullStore.ready := cmd.bits(0).deps.pullStore

          when (current_dataflow === Dataflow.OS.id.U) {
            in_s_flush := rs2s(0)(tagWidth-1, 0) =/= tag_garbage
          }

          fire_count_started := true.B
          control_state := compute
        }

        // Overlap compute and preload
        .elsewhen(DoComputes(0) && cmd.valid(1) && DoPreloads(1) && pull_deps_ready(1) && push_deps_ready(1)) {
          perform_mul_pre := true.B
          start_inputting_ab := true.B
          start_inputting_d := true.B

          io.pullLoad.ready := cmd.bits(1).deps.pullLoad
          io.pullStore.ready := cmd.bits(1).deps.pullStore

          when (current_dataflow === Dataflow.OS.id.U) {
            in_s_flush := rs2s(1)(tagWidth - 1, 0) =/= tag_garbage
          }

          fire_count_started := true.B
          control_state := compute
        }

        // Single mul
        .elsewhen(DoComputes(0)) {
          perform_single_mul := true.B
          start_inputting_ab := true.B

          fire_count_started := true.B
          control_state := compute
        }
      }
    }
    is (compute) {
      // Only preloading
      when(perform_single_preload) {
        start_inputting_d := true.B

        when (about_to_fire_all_rows) {
          cmd.pop := 1.U
          control_state := waiting_for_cmd
        }
      }

      // Overlapping
      .elsewhen(perform_mul_pre)
      {
        start_inputting_ab := true.B
        start_inputting_d := true.B

        when (about_to_fire_all_rows) {
          cmd.pop := 2.U
          control_state := waiting_for_cmd
        }
      }

      // Only compute
      .elsewhen(perform_single_mul) {
        start_inputting_ab := true.B

        when(fired_all_rows) {
          perform_single_mul := false.B

          start_inputting_ab := false.B

          fire_count_started := false.B
          cmd.pop := 1.U
          control_state := flush
        }
      }
    }
    is (flush) {
      when(mesh.io.flush.ready) {
        mesh.io.flush.valid := true.B
        mesh.io.s := in_s_flush
        control_state := flushing
      }
    }
    is (flushing) {
      when(mesh.io.flush.ready) {
        control_state := waiting_for_cmd
      }
    }
  }

  // Computing logic
  when (perform_mul_pre || perform_single_mul || perform_single_preload) {
    // Default inputs
    mesh.io.a.valid := true.B
    mesh.io.b.valid := true.B
    mesh.io.d.valid := true.B
    mesh.io.tag_in.valid := true.B

    mesh.io.a.bits := dataA.asTypeOf(Vec(meshRows, Vec(tileRows, inputType)))
    mesh.io.b.bits := dataB.asTypeOf(Vec(meshColumns, Vec(tileColumns, inputType)))
    mesh.io.d.bits := dataD.asTypeOf(Vec(meshColumns, Vec(tileColumns, inputType)))

    mesh.io.tag_in.bits.pushLoad := VecInit(pushLoads take 2)(preload_cmd_place)
    mesh.io.tag_in.bits.pushStore := VecInit(pushStores take 2)(preload_cmd_place)
    mesh.io.tag_in.bits.tag := c_address_rs2.asUInt()
  }

  when (perform_single_preload) {
    mesh.io.a.bits := (0.U).asTypeOf(Vec(meshRows, Vec(tileRows, inputType)))
    mesh.io.b.bits := (0.U).asTypeOf(Vec(meshColumns, Vec(tileColumns, inputType)))
  }

  when (perform_single_mul) {
    mesh.io.tag_in.bits.pushLoad := false.B
    mesh.io.tag_in.bits.pushStore := false.B
    mesh.io.tag_in.bits.tag := tag_garbage
  }

  // Scratchpad writes
  val w_address = mesh.io.tag_out.tag.asTypeOf(spaddr)
  val w_bank_number = w_address.spbank
  val w_bank_address = w_address.sprow
  val current_w_bank_address = Mux(current_dataflow === Dataflow.WS.id.U, w_bank_address + output_counter.value,
    w_bank_address + block_size.U - 1.U - output_counter.value)

  for(i <- 0 until sp_banks) {
    io.write(i).en := start_array_outputting && w_bank_number === i.U
    io.write(i).addr := current_w_bank_address
    io.write(i).data := mesh.io.out.bits.asUInt()
  }

  when(mesh.io.out.fire() && mesh.io.tag_out.tag =/= tag_garbage) {
    when(output_counter.inc()) {
      io.pushLoad.valid := mesh.io.tag_out.pushLoad
      io.pushStore.valid := mesh.io.tag_out.pushStore

      assert(!mesh.io.tag_out.pushLoad || io.pushLoad.ready)
      assert(!mesh.io.tag_out.pushStore || io.pushStore.ready)
    }
    start_array_outputting := true.B
  }
}