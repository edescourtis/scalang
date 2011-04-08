package scalang

import java.util.concurrent.atomic._
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import org.jboss.{netty => netty}
import netty.channel._
import netty.bootstrap._
import socket.nio.NioServerSocketChannelFactory
import java.util.concurrent._
import scalang.util.Log
import scalang.node._
import org.cliffc.high_scale_lib.NonBlockingHashMap
import org.jetlang._
import fibers.PoolFiberFactory
import core.BatchExecutorImpl
import scala.collection.JavaConversions._
import scalang.epmd._
import scalang.util._

object Node {
  def apply(name : Symbol) {
    
  }
}

trait Node {
  def name : Symbol
  def cookie : String
  def spawn[T <: Process](implicit mf : Manifest[T]) : Pid
  def spawn[T <: Process](regName : String)(implicit mf : Manifest[T]) : Pid
  def spawn[T <: Process](regName : Symbol)(implicit mf : Manifest[T]) : Pid
  def spawnMbox : Mailbox
  def spawnMbox(regName : String) : Mailbox
  def spawnMbox(regName : Symbol) : Mailbox
  def register(regName : String, pid : Pid)
  def register(regName : Symbol, pid : Pid)
  def getNames : Set[Symbol]
  def whereis(name : Symbol) : Option[Pid]
  def ping(node : Symbol, timeout : Long) : Boolean
  def nodes : Set[Symbol]
  def makeRef : Reference
  def isAlive(pid : Pid) : Boolean
  def shutdown
}

class ErlangNode(val name : Symbol, val cookie : String) extends Node with Log with ExitListener with SendListener with LinkListener {
  var creation : Int = 0
  val processes = new NonBlockingHashMap[Pid,ProcessLike]
  val registeredNames = new NonBlockingHashMap[Symbol,Pid]
  val channels = new NonBlockingHashMap[Symbol,Channel]
  val pidCount = new AtomicInteger(0)
  val pidSerial = new AtomicInteger(0)
  val executor = Executors.newCachedThreadPool
  val factory = new PoolFiberFactory(executor)
  val server = new ErlangNodeServer(this)
  val localEpmd = Epmd("localhost")
  localEpmd.alive(server.port, splitNodename(name)) match {
    case Some(c) => creation = c
    case None => throw new ErlangNodeException("EPMD alive announcement failed.")
  }
  val referenceCounter = new ReferenceCounter(name, creation)
  val netKernel = spawn[NetKernel]('net_kernel)
  
  def shutdown {
    localEpmd.close
    for ((node,channel) <- channels) {
      channel.close
    }
    for((pid,process) <- processes) {
      process.exit('node_shutdown)
    }
  }
  
  def finalize {
    shutdown
  }
  
  def spawnMbox : Mailbox = {
    val p = createPid
    val n = this
    val box = new Mailbox(new ProcessContext {
      val pid = p
      val referenceCounter = n.referenceCounter
      val node = n
      val fiber = null
    })
    box.addExitListener(this)
    box.addSendListener(this)
    box.addLinkListener(this)
    processes.put(p, box)
    box
  }
  
  def spawnMbox(regName : String) : Mailbox = spawnMbox(Symbol(regName))
  def spawnMbox(regName : Symbol) : Mailbox = {
    val mbox = spawnMbox
    register(regName, mbox.self)
    mbox
  }
  
  def createPid : Pid = {
    val id = pidCount.getAndIncrement
    val serial = pidSerial.getAndIncrement
    Pid(name,id,serial,creation)
  }
  
  //node external interface
  def spawn[T <: Process](implicit mf : Manifest[T]) : Pid = {
    val pid = createPid
    val process = createProcess(mf.erasure.asInstanceOf[Class[T]], pid, None)
    process.addExitListener(this)
    process.addSendListener(this)
    process.addLinkListener(this)
    processes.put(pid, process)
    process.fiber.start
    pid
  }
  
  def spawn[T <: Process](regName : Symbol)(implicit mf : Manifest[T]) : Pid = {
    val pid = spawn[T](mf)
    registeredNames.put(regName, pid)
    pid
  }
  
  def spawn[T <: Process](regName : String)(implicit mf : Manifest[T]) : Pid = {
    spawn(Symbol(regName))(mf)
  }
  
  protected def createProcess[T <: Process](clazz : Class[T], p : Pid, tpc : Option[ThreadPoolConfig]) : Process = {
    val f = tpc.map( config =>
      factory.create(new BatchPoolExecutor(config.coreSize, config.maxSize, config.keepAlive))
    ).getOrElse(factory.create)
    val constructor = clazz.getConstructor(classOf[ProcessContext])
    val n = this
    val ctx = new ProcessContext {
      val pid = p
      val referenceCounter = n.referenceCounter
      val node = n
      val fiber = f
    }
    constructor.newInstance(ctx)
  }
  
  def register(regName : String, pid : Pid) {
    register(Symbol(regName), pid)
  }
  
  def register(regName : Symbol, pid : Pid) {
    registeredNames.put(regName, pid)
  }
  
  def getNames : Set[Symbol] = {
    registeredNames.keySet.toSet.asInstanceOf[Set[Symbol]]
  }
  
  def registerConnection(name : Symbol, channel : Channel) {
    channels.put(name, channel)
  }
  
  def whereis(regName : Symbol) : Option[Pid] = {
    Option(registeredNames.get(regName))
  }
  
  def ping(node : Symbol, timeout : Long) : Boolean = {
    val mbox = spawnMbox
    val ref = makeRef
    mbox.send('net_kernel, (Symbol("$gen_call"), (mbox.self, ref), ('is_auth, name)))
    val result = mbox.receive(timeout) match {
      case Some((ref, 'yes)) => true
      case m => 
        false
    }
    mbox.exit('normal)
    result
  }
  
  def nodes : Set[Symbol] = {
    channels.keySet.toSet.asInstanceOf[Set[Symbol]]
  }
  
  def deliverLink(from : Pid, to : Pid) {
    if (from == to) {
      warn("Trying to link a pid to itself: " + from)
      return
    }
    
    if (isLocal(to)) {
      for (p <- process(to)) {
        p.linkWithoutNotify(from)
      }
    } else {
      getOrConnectAndSend(to.node, LinkMessage(from, to))
    }
  }
  
  //node internal interface
  def link(from : Pid, to : Pid) {
    if (from == to) {
      warn("Trying to link a pid to itself: " + from)
      return
    }
    if (!isLocal(from) && !isLocal(to)) {
      warn("Trying to link non-local pids: " + from + " -> " + to)
      return
    }
    
    for(p <- process(from)) {
      p.link(to)
    }
    
    for(p <- process(to)) {
      p.link(from)
    }
  }
  
  def send(to : Pid, msg : Any) = handleSend(to, msg)
  def send(to : Symbol, msg : Any) = handleSend(to, msg)
  def send(to : (Symbol,Symbol), from : Pid, msg : Any) = handleSend(to, from, msg)
  
  def handleSend(to : Pid, msg : Any) {
    if (isLocal(to)) {
      val process = processes.get(to)
      if (process != null) {
        process.handleMessage(msg)
      }
    } else {
      getOrConnectAndSend(to.node, SendMessage(to, msg))
    }
  }
  
  def handleSend(to : Symbol, msg : Any) {
    for (pid <- whereis(to)) {
      handleSend(pid, msg)
    }
  }
  
  def makeRef : Reference = {
    referenceCounter.makeRef
  }
  
  def handleSend(dest : (Symbol,Symbol), from : Pid, msg : Any) {
    val (regName,peer) = dest
    getOrConnectAndSend(peer, RegSend(from, regName, msg))
  }
  
  def handleExit(from : Pid, reason : Any) {
    Option(processes.get(from)) match {
      case Some(pf : Process) =>
        val fiber = pf.fiber
        fiber.dispose
      case _ =>
        Unit
    }
    processes.remove(from)
  }
  
  def break(from : Pid, to : Pid, reason : Any) {
    if (isLocal(to)) {
      for (proc <- process(to)) {
        proc.handleExit(from, reason)
      }
    } else {
      getOrConnectAndSend(to.node, ExitMessage(from,to,reason))
    }
  }
  
  def process(pid : Pid) : Option[ProcessLike] = {
    Option(processes.get(pid))
  }
  
  def unlink(from : Pid, to : Pid) {
    for (p <- process(from)) {
      p.unlink(to)
    }
    
    for (p <- process(to)) {
      p.unlink(from)
    }
  }
  
  def isLocal(pid : Pid) : Boolean = {
    pid.node == name && pid.creation == creation
  }
  
  def disconnected(peer : Symbol) {
    channels.remove(peer)
  }
  
  def getOrConnectAndSend(peer : Symbol, msg : Any) {
    Option(channels.get(peer)) match {
      case Some(channel) => channel.write(msg)
      case None => connectAndSend(peer, Some(msg))
    }
  }
  
  def connectAndSend(peer : Symbol, msg : Option[Any] = None) {
    val hostname = splitHostname(peer).getOrElse(throw new ErlangNodeException("Cannot resolve peer with no hostname: " + peer.name))
    val peerName = splitNodename(peer)
    val port = Epmd(hostname).lookupPort(peerName).getOrElse(throw new ErlangNodeException("Cannot lookup peer: " + peer.name))
    val client = new ErlangNodeClient(this, hostname, port, msg)
  }
  
  def splitNodename(peer : Symbol) : String = {
    val parts = peer.name.split('@')
    if (parts.length < 2) {
      peer.name
    } else {
      parts(0)
    }
  }
  
  def splitHostname(peer : Symbol) : Option[String] = {
    val parts = peer.name.split('@')
    if (parts.length < 2) {
      None
    } else {
      Some(parts(1))
    }
  }
  
  def isAlive(pid : Pid) : Boolean = {
    process(pid) match {
      case Some(_) => true
      case None => false
    }
  }
}

class ErlangNodeException(msg : String) extends Exception(msg)