package fhe

import chisel3._
import chisel3.util._

/**
  * Homomorphic Logic Gates
  * 
  * TFHE enables efficient homomorphic evaluation of logic gates.
  * Each gate takes encrypted bits as input and produces an encrypted
  * bit as output, without ever revealing the plaintext values.
  * 
  * The key operations are:
  *   - Addition of LWE ciphertexts (for XOR-like operations)
  *   - Scalar multiplication (for constant multiplication)
  *   - Programmable bootstrapping (for arbitrary gate evaluation)
  * 
  * Gate encodings use the torus representation where:
  *   - 0 is encoded as 0
  *   - 1 is encoded as 1/2 (or q/2 in integer representation)
  * 
  * After linear operations, bootstrapping "cleans" the result by
  * rounding to the nearest valid encoding.
  */

/**
  * Homomorphic NOT Gate
  * 
  * NOT(c) = (0, 1/4) - c
  * 
  * This flips the encoded bit: if c encrypts m, result encrypts NOT(m).
  * Does not require bootstrapping since it's a linear operation.
  * 
  * @param n LWE dimension
  * @param width Bit width
  * @param modulus The modulus q
  */
class HomomorphicNOT(n: Int, width: Int, modulus: Int) extends Module {
  val io = IO(new Bundle {
    val in = Input(new LWECiphertext(n, width))
    val out = Output(new LWECiphertext(n, width))
  })
  
  // Constant 1/4 in torus representation = q/4
  val quarter = (modulus / 4).U(width.W)
  
  // out.a = -in.a
  for (i <- 0 until n) {
    io.out.a(i) := Mux(io.in.a(i) === 0.U, 0.U, modulus.U - io.in.a(i))
  }
  
  // out.b = q/4 - in.b mod q
  val bDiff = quarter.asSInt - io.in.b.asSInt
  io.out.b := Mux(bDiff < 0.S, (bDiff + modulus.S).asUInt, bDiff.asUInt)
}

/**
  * Homomorphic NAND Gate (Pre-Bootstrap)
  * 
  * NAND(c1, c2) = (0, 5/8) - c1 - c2
  * 
  * After this linear operation, the phase encodes:
  *   - m1=0, m2=0: 5/8 → rounds to 1/2 (=1)
  *   - m1=0, m2=1: 5/8 - 1/2 = 1/8 → rounds to 0
  *   - m1=1, m2=0: 5/8 - 1/2 = 1/8 → rounds to 0
  *   - m1=1, m2=1: 5/8 - 1/2 - 1/2 = -3/8 = 5/8 → rounds to 1/2... wait
  * 
  * Actually: NAND = (0, 5q/8) - c1 - c2
  * Needs bootstrapping to convert to proper encoding.
  * 
  * @param n LWE dimension
  * @param width Bit width
  * @param modulus The modulus q
  */
class HomomorphicNANDLinear(n: Int, width: Int, modulus: Int) extends Module {
  val io = IO(new Bundle {
    val c1 = Input(new LWECiphertext(n, width))
    val c2 = Input(new LWECiphertext(n, width))
    val out = Output(new LWECiphertext(n, width))
  })
  
  // Constant 5q/8
  val fiveEighths = ((5 * modulus) / 8).U(width.W)
  
  // out.a = -(c1.a + c2.a) mod q
  for (i <- 0 until n) {
    val sum = io.c1.a(i) +& io.c2.a(i)
    val sumMod = Mux(sum >= modulus.U, sum - modulus.U, sum)
    io.out.a(i) := Mux(sumMod === 0.U, 0.U, modulus.U - sumMod)
  }
  
  // out.b = 5q/8 - c1.b - c2.b mod q
  val bSub = fiveEighths.asSInt - io.c1.b.asSInt - io.c2.b.asSInt
  val bMod = Mux(bSub < 0.S, 
                 Mux(bSub < (-modulus).S, (bSub + (2 * modulus).S).asUInt, (bSub + modulus.S).asUInt),
                 Mux(bSub >= modulus.S, (bSub - modulus.S).asUInt, bSub.asUInt))
  io.out.b := bMod
}

/**
  * Homomorphic AND Gate (Pre-Bootstrap)
  * 
  * AND(c1, c2) = c1 + c2 - (0, q/8)
  * 
  * After this, the phase encodes:
  *   - m1=0, m2=0: -q/8 → rounds to 0
  *   - m1=0, m2=1: q/2 - q/8 = 3q/8 → rounds to 1/2? (need bootstrap)
  *   - m1=1, m2=0: q/2 - q/8 = 3q/8 → rounds to 1/2? 
  *   - m1=1, m2=1: q/2 + q/2 - q/8 = 7q/8 → rounds to 1
  * 
  * Requires bootstrapping to complete.
  * 
  * @param n LWE dimension
  * @param width Bit width
  * @param modulus The modulus q
  */
class HomomorphicANDLinear(n: Int, width: Int, modulus: Int) extends Module {
  val io = IO(new Bundle {
    val c1 = Input(new LWECiphertext(n, width))
    val c2 = Input(new LWECiphertext(n, width))
    val out = Output(new LWECiphertext(n, width))
  })
  
  // Constant q/8
  val eighth = (modulus / 8).U(width.W)
  
  // out.a = c1.a + c2.a mod q
  for (i <- 0 until n) {
    val sum = io.c1.a(i) +& io.c2.a(i)
    io.out.a(i) := Mux(sum >= modulus.U, sum - modulus.U, sum)
  }
  
  // out.b = c1.b + c2.b - q/8 mod q
  val bSum = io.c1.b +& io.c2.b
  val bSumMod = Mux(bSum >= modulus.U, bSum - modulus.U, bSum)
  val bResult = bSumMod.asSInt - eighth.asSInt
  io.out.b := Mux(bResult < 0.S, (bResult + modulus.S).asUInt, bResult.asUInt)
}

/**
  * Homomorphic OR Gate (Pre-Bootstrap)
  * 
  * OR(c1, c2) = c1 + c2 + (0, q/8)
  * 
  * @param n LWE dimension
  * @param width Bit width
  * @param modulus The modulus q
  */
class HomomorphicORLinear(n: Int, width: Int, modulus: Int) extends Module {
  val io = IO(new Bundle {
    val c1 = Input(new LWECiphertext(n, width))
    val c2 = Input(new LWECiphertext(n, width))
    val out = Output(new LWECiphertext(n, width))
  })
  
  val eighth = (modulus / 8).U(width.W)
  
  // out.a = c1.a + c2.a mod q
  for (i <- 0 until n) {
    val sum = io.c1.a(i) +& io.c2.a(i)
    io.out.a(i) := Mux(sum >= modulus.U, sum - modulus.U, sum)
  }
  
  // out.b = c1.b + c2.b + q/8 mod q
  val bSum = io.c1.b +& io.c2.b +& eighth
  val bMod = Mux(bSum >= (2 * modulus).U, bSum - (2 * modulus).U,
             Mux(bSum >= modulus.U, bSum - modulus.U, bSum))
  io.out.b := bMod
}

/**
  * Homomorphic XOR Gate (Pre-Bootstrap)
  * 
  * XOR(c1, c2) = 2*(c1 + c2)
  * 
  * Doubling maps:
  *   - 0 + 0 = 0 → 0
  *   - 0 + 1/2 = 1/2 → 1 (wraps to 0 as torus value, but actually goes to 1/2 in clean encoding)
  *   - 1/2 + 0 = 1/2 → 1
  *   - 1/2 + 1/2 = 1 → 0 (mod 1)
  * 
  * @param n LWE dimension
  * @param width Bit width
  * @param modulus The modulus q
  */
class HomomorphicXORLinear(n: Int, width: Int, modulus: Int) extends Module {
  val io = IO(new Bundle {
    val c1 = Input(new LWECiphertext(n, width))
    val c2 = Input(new LWECiphertext(n, width))
    val out = Output(new LWECiphertext(n, width))
  })
  
  // out = 2 * (c1 + c2)
  for (i <- 0 until n) {
    val sum = io.c1.a(i) +& io.c2.a(i)
    val sumMod = Mux(sum >= modulus.U, sum - modulus.U, sum)
    val doubled = sumMod << 1
    io.out.a(i) := Mux(doubled >= modulus.U, doubled - modulus.U, doubled)
  }
  
  val bSum = io.c1.b +& io.c2.b
  val bSumMod = Mux(bSum >= modulus.U, bSum - modulus.U, bSum)
  val bDoubled = bSumMod << 1
  io.out.b := Mux(bDoubled >= modulus.U, bDoubled - modulus.U, bDoubled)
}

/**
  * Homomorphic XNOR Gate (Pre-Bootstrap)
  * 
  * XNOR(c1, c2) = NOT(XOR(c1, c2)) = (0, q/4) - 2*(c1 + c2)
  * 
  * @param n LWE dimension
  * @param width Bit width
  * @param modulus The modulus q
  */
class HomomorphicXNORLinear(n: Int, width: Int, modulus: Int) extends Module {
  val io = IO(new Bundle {
    val c1 = Input(new LWECiphertext(n, width))
    val c2 = Input(new LWECiphertext(n, width))
    val out = Output(new LWECiphertext(n, width))
  })
  
  val quarter = (modulus / 4).U(width.W)
  
  // XOR then NOT
  val xor = Module(new HomomorphicXORLinear(n, width, modulus))
  xor.io.c1 := io.c1
  xor.io.c2 := io.c2
  
  val not = Module(new HomomorphicNOT(n, width, modulus))
  not.io.in := xor.io.out
  
  io.out := not.io.out
}

/**
  * Homomorphic NOR Gate (Pre-Bootstrap)
  * 
  * NOR(c1, c2) = NOT(OR(c1, c2))
  * 
  * @param n LWE dimension
  * @param width Bit width
  * @param modulus The modulus q
  */
class HomomorphicNORLinear(n: Int, width: Int, modulus: Int) extends Module {
  val io = IO(new Bundle {
    val c1 = Input(new LWECiphertext(n, width))
    val c2 = Input(new LWECiphertext(n, width))
    val out = Output(new LWECiphertext(n, width))
  })
  
  val orGate = Module(new HomomorphicORLinear(n, width, modulus))
  orGate.io.c1 := io.c1
  orGate.io.c2 := io.c2
  
  val notGate = Module(new HomomorphicNOT(n, width, modulus))
  notGate.io.in := orGate.io.out
  
  io.out := notGate.io.out
}

/**
  * Homomorphic MUX Gate
  * 
  * MUX(sel, c0, c1) = sel ? c1 : c0
  *                  = (1 - sel) * c0 + sel * c1
  *                  = c0 + sel * (c1 - c0)
  * 
  * This requires bootstrapping for the multiplication by sel.
  * 
  * @param n LWE dimension
  * @param width Bit width
  * @param modulus The modulus q
  */
class HomomorphicMUXLinear(n: Int, width: Int, modulus: Int) extends Module {
  val io = IO(new Bundle {
    val sel = Input(new LWECiphertext(n, width))
    val c0 = Input(new LWECiphertext(n, width))  // Selected when sel=0
    val c1 = Input(new LWECiphertext(n, width))  // Selected when sel=1
    // Note: Full MUX requires external product with sel, handled by bootstrapping
    // This module provides the linear combination for bootstrapping
    val out = Output(new LWECiphertext(n, width))
  })
  
  // Linear part: c1 - c0
  val diff = Module(new LWESubtract(n, width, modulus))
  diff.io.ct1 := io.c1
  diff.io.ct2 := io.c0
  
  io.out := diff.io.result
  // Full MUX: externally compute sel ⊡ diff, then add c0
}

/**
  * Gate Selector
  * 
  * Selects and executes a specific homomorphic gate operation.
  * 
  * @param n LWE dimension
  * @param width Bit width
  * @param modulus The modulus q
  */
class GateSelector(n: Int, width: Int, modulus: Int) extends Module {
  val io = IO(new Bundle {
    val c1 = Input(new LWECiphertext(n, width))
    val c2 = Input(new LWECiphertext(n, width))
    val gateType = Input(UInt(4.W))  // 0:NOT, 1:AND, 2:OR, 3:XOR, 4:NAND, 5:NOR, 6:XNOR
    val out = Output(new LWECiphertext(n, width))
  })
  
  // Instantiate all gates
  val notGate = Module(new HomomorphicNOT(n, width, modulus))
  val andGate = Module(new HomomorphicANDLinear(n, width, modulus))
  val orGate = Module(new HomomorphicORLinear(n, width, modulus))
  val xorGate = Module(new HomomorphicXORLinear(n, width, modulus))
  val nandGate = Module(new HomomorphicNANDLinear(n, width, modulus))
  val norGate = Module(new HomomorphicNORLinear(n, width, modulus))
  val xnorGate = Module(new HomomorphicXNORLinear(n, width, modulus))
  
  // Connect inputs
  notGate.io.in := io.c1
  
  andGate.io.c1 := io.c1
  andGate.io.c2 := io.c2
  
  orGate.io.c1 := io.c1
  orGate.io.c2 := io.c2
  
  xorGate.io.c1 := io.c1
  xorGate.io.c2 := io.c2
  
  nandGate.io.c1 := io.c1
  nandGate.io.c2 := io.c2
  
  norGate.io.c1 := io.c1
  norGate.io.c2 := io.c2
  
  xnorGate.io.c1 := io.c1
  xnorGate.io.c2 := io.c2
  
  // Output mux
  io.out := MuxLookup(io.gateType, notGate.io.out, Seq(
    0.U -> notGate.io.out,
    1.U -> andGate.io.out,
    2.U -> orGate.io.out,
    3.U -> xorGate.io.out,
    4.U -> nandGate.io.out,
    5.U -> norGate.io.out,
    6.U -> xnorGate.io.out
  ))
}

/**
  * Homomorphic Full Adder
  * 
  * Computes sum and carry for encrypted bits:
  *   sum = a XOR b XOR cin
  *   cout = (a AND b) OR (cin AND (a XOR b))
  * 
  * Requires multiple bootstrapping operations.
  * 
  * @param n LWE dimension
  * @param width Bit width
  * @param modulus The modulus q
  */
class HomomorphicFullAdder(n: Int, width: Int, modulus: Int) extends Module {
  val io = IO(new Bundle {
    val a = Input(new LWECiphertext(n, width))
    val b = Input(new LWECiphertext(n, width))
    val cin = Input(new LWECiphertext(n, width))
    // Linear outputs before bootstrapping
    val sumLinear = Output(new LWECiphertext(n, width))
    val coutLinear = Output(new LWECiphertext(n, width))
  })
  
  // a XOR b
  val xor1 = Module(new HomomorphicXORLinear(n, width, modulus))
  xor1.io.c1 := io.a
  xor1.io.c2 := io.b
  
  // (a XOR b) XOR cin = sum
  val xor2 = Module(new HomomorphicXORLinear(n, width, modulus))
  xor2.io.c1 := xor1.io.out
  xor2.io.c2 := io.cin
  
  io.sumLinear := xor2.io.out
  
  // a AND b
  val and1 = Module(new HomomorphicANDLinear(n, width, modulus))
  and1.io.c1 := io.a
  and1.io.c2 := io.b
  
  // cin AND (a XOR b)
  val and2 = Module(new HomomorphicANDLinear(n, width, modulus))
  and2.io.c1 := io.cin
  and2.io.c2 := xor1.io.out
  
  // (a AND b) OR (cin AND (a XOR b)) = cout
  val orGate = Module(new HomomorphicORLinear(n, width, modulus))
  orGate.io.c1 := and1.io.out
  orGate.io.c2 := and2.io.out
  
  io.coutLinear := orGate.io.out
}

object HomomorphicGatesMain extends App {
  val n = 512
  val width = 10
  val q = 1024
  
  emitVerilog(new HomomorphicNOT(n, width, q), Array("--target-dir", "generated/fhe"))
  emitVerilog(new HomomorphicNANDLinear(n, width, q), Array("--target-dir", "generated/fhe"))
  emitVerilog(new GateSelector(n, width, q), Array("--target-dir", "generated/fhe"))
}
