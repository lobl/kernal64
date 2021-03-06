package ucesoft.cbm

import java.awt.Color
import java.awt.event._
import java.io._
import java.util.zip.{GZIPInputStream, GZIPOutputStream}
import java.util.{Properties, ServiceLoader}

import javax.swing._
import javax.swing.filechooser.FileFilter
import ucesoft.cbm.cpu.{CPU65xx, Memory, ROM}
import ucesoft.cbm.expansion._
import ucesoft.cbm.expansion.cpm.CPMCartridge
import ucesoft.cbm.formats._
import ucesoft.cbm.game.{GamePlayer, GameUI}
import ucesoft.cbm.misc._
import ucesoft.cbm.peripheral.bus.IECBus
import ucesoft.cbm.peripheral.c2n.Datassette
import ucesoft.cbm.peripheral.cia.CIA
import ucesoft.cbm.peripheral.controlport.JoystickSettingDialog
import ucesoft.cbm.peripheral.controlport.Joysticks._
import ucesoft.cbm.peripheral.drive._
import ucesoft.cbm.peripheral.keyboard.Keyboard
import ucesoft.cbm.peripheral.printer.{MPS803, MPS803GFXDriver, MPS803ROM}
import ucesoft.cbm.peripheral.rs232._
import ucesoft.cbm.peripheral.vic.Palette
import ucesoft.cbm.peripheral.vic.Palette.PaletteType
import ucesoft.cbm.peripheral.{controlport, keyboard, vic}
import ucesoft.cbm.remote.RemoteC64
import ucesoft.cbm.trace.{InspectPanelDialog, TraceDialog, TraceListener}

import scala.util.{Failure, Success}

object CBMComputer {
  def turnOn(computer : => CBMComputer,args:Array[String]) : Unit = {
    UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())
    val cbm = computer
    try {
      cbm.turnOn(args)
    }
    catch {
      case i:Settings.SettingIllegalArgumentException =>
        println(s"Bad command line argument: ${i.getMessage}")
        sys.exit(1)
      case t:Throwable =>
        cbm.errorHandler(t)
        if (cbm.isHeadless) sys.exit(1)
    }
  }
}

trait CBMComputer extends CBMComponent with GamePlayer { cbmComputer =>
  protected val APPLICATION_NAME : String
  protected val CONFIGURATION_FILENAME : String
  protected val CONFIGURATION_LASTDISKDIR = "lastDiskDirectory"
  protected val CONFIGURATION_FRAME_XY = "frame.xy"
  protected val CONFIGURATION_FRAME_DIM = "frame.dim"
  protected val CONFIGURATION_KEYB_MAP_FILE = "keyb.map.file"
  protected val CONFIGURATION_GMOD2_FILE = "gmod2.file"
  protected val CONFIGURATION_AUTOSAVE = "autosave"

  protected val PRG_RUN_DELAY_CYCLES = 2200000

  // -------------- MENU ITEMS -----------------
  protected val maxSpeedItem = new JCheckBoxMenuItem("Warp mode")
  protected val loadFileItems = Array(new JMenuItem("Load file from attached disk 8 ..."), new JMenuItem("Load file from attached disk 9 ..."))
  protected val tapeMenu = new JMenu("Tape control...")
  protected val detachCtrItem = new JMenuItem("Detach cartridge")
  protected val cartMenu = new JMenu("Cartridge")

  protected val settings = new ucesoft.cbm.misc.Settings

  protected lazy val configuration = {
    val props = new Properties
    val propsFile = new File(new File(scala.util.Properties.userHome),CONFIGURATION_FILENAME)
    if (propsFile.exists) {
      try {
        props.load(new FileReader(propsFile))
      }
      catch {
        case _:IOException =>
      }
    }
    ROM.props = props
    props
  }

  protected var cartButtonRequested = false
  protected var headless = false // used with --testcart command options
  protected var cpujamContinue = false // used with --cpujam-continue
  protected var zoomOverride = false // used with --screen-dim
  protected var sidCycleExact = false // used with --sid-cycle-exact
  protected var loadStateFromOptions = false // used with --load-state
  protected var traceOption = false // used with --trace

  // memory & main cpu
  protected val mmu : Memory
  protected lazy val cpu = CPU65xx.make(mmu)
  // main chips
  protected val clock = Clock.setSystemClock(Some(errorHandler _))(mainLoop _)
  protected var vicChip : vic.VIC = _
  protected var cia1,cia2 : CIA = _
  protected val cia12Running = Array(true,true)
  protected val sid = new ucesoft.cbm.peripheral.sid.SID
  protected var display : vic.Display = _
  protected var gifRecorder : JDialog = _
  protected val nmiSwitcher = new NMISwitcher(cpu.nmiRequest _)
  protected val irqSwitcher = new IRQSwitcher(cpu.irqRequest _)
  protected val keybMapper : keyboard.KeyboardMapper
  protected lazy val keyb = new keyboard.Keyboard(keybMapper,nmiSwitcher.keyboardNMIAction _)	// key listener

  protected val bus = new IECBus
  protected var dma = false
  protected val expansionPort = ExpansionPort.getExpansionPort

  protected lazy val displayFrame = {
    val f = new JFrame(s"$APPLICATION_NAME " + ucesoft.cbm.Version.VERSION)
    f.addWindowListener(new WindowAdapter {
      override def windowClosing(e:WindowEvent) : Unit = turnOff
    })
    f.setIconImage(new ImageIcon(getClass.getResource("/resources/commodore.png")).getImage)
    f
  }

  protected var resetSettingsActions : List[() => Unit] = Nil

  // ----------------- REMOTE ------------------
  protected var remote : Option[RemoteC64] = None
  // -------------------- TRACE ----------------
  protected var traceDialog : TraceDialog = _
  protected var diskTraceDialog : TraceDialog = _
  protected var inspectDialog : InspectPanelDialog = _
  protected var traceItem,traceDiskItem : JCheckBoxMenuItem = _
  // -------------------- DISK -----------------
  protected val drivesRunning = Array(true,true)
  protected val drivesEnabled = Array(true,true)
  protected lazy val diskFlusher = new FloppyFlushUI(displayFrame)
  protected val driveLeds = Array(new DriveLed,new DriveLed)
  protected val floppyComponents = Array.ofDim[FloppyComponent](2)
  protected val diskProgressPanels = Array(new DriveLoadProgressPanel,new DriveLoadProgressPanel)
  protected val flyerIEC = new FlyerIEC(bus,file => attachDiskFile(0,file,false,None))
  protected var isFlyerEnabled = false
  protected val drives : Array[Drive with TraceListener] = Array.ofDim(2)
  protected var device10Drive : Drive = _
  protected var device10DriveEnabled = false
  protected var canWriteOnDisk = true
  // -------------------- TAPE -----------------
  protected var datassette : Datassette = _
  // ----------------- RS-232 ------------------
  protected val rs232 = BridgeRS232
  protected val AVAILABLE_RS232 : Array[RS232] = Array(//UP9600,
    TelnetRS232,
    TCPRS232,
    FileRS232,
    SwiftLink.getSL(nmiSwitcher.expansionPortNMI,None),
    SwiftLink.getSL(nmiSwitcher.expansionPortNMI _,Some(REU.getREU(REU.REU_1750,mmu,setDMA _,irqSwitcher.expPortIRQ _,None))),
    ProcessRS232)
  // -------------------- PRINTER --------------
  protected var printerEnabled = false
  protected val printerGraphicsDriver = new MPS803GFXDriver(new MPS803ROM)
  protected val printer = new MPS803(bus,printerGraphicsDriver)
  protected lazy val printerDialog = {
    val dialog = new JDialog(displayFrame,"Print preview")
    val sp = new JScrollPane(printerGraphicsDriver)
    sp.getViewport.setBackground(Color.BLACK)
    dialog.getContentPane.add("Center",sp)
    printerGraphicsDriver.checkSize
    val buttonPanel = new JPanel
    val exportPNGBUtton = new JButton("Export as PNG")
    buttonPanel.add(exportPNGBUtton)
    exportPNGBUtton.addActionListener(_ => printerSaveImage )
    val clearButton = new JButton("Clear")
    buttonPanel.add(clearButton)
    clearButton.addActionListener(_ => printerGraphicsDriver.clearPages )
    dialog.getContentPane.add("South",buttonPanel)
    dialog.pack
    dialog
  }
  // -------------- AUDIO ----------------------
  protected lazy val volumeDialog : JDialog = VolumeSettingsPanel.getDialog(displayFrame,sid.getDriver)
  // ------------ Control Port -----------------------
  protected lazy val gameControlPort = new controlport.GamePadControlPort(configuration)
  protected val keypadControlPort = controlport.ControlPort.keypadControlPort
  protected lazy val keyboardControlPort = controlport.ControlPort.userDefinedKeyControlPort(configuration)
  protected val controlPortA = new controlport.ControlPortBridge(keypadControlPort,"Control Port 1")
  protected lazy val controlPortB = new controlport.ControlPortBridge(gameControlPort,"Control port 2")
  // -------------- Light Pen -------------------------
  protected val LIGHT_PEN_NO_BUTTON = 0
  protected val LIGHT_PEN_BUTTON_UP = 1
  protected val LIGHT_PEN_BUTTON_LEFT = 2
  protected var lightPenButtonEmulation = LIGHT_PEN_NO_BUTTON

  protected class LightPenButtonListener extends MouseAdapter with CBMComponent {
    val componentID = "Light pen"
    val componentType = CBMComponentType.INPUT_DEVICE

    override def mousePressed(e:MouseEvent) : Unit = {
      lightPenButtonEmulation match {
        case LIGHT_PEN_NO_BUTTON =>
        case LIGHT_PEN_BUTTON_UP => controlPortB.emulateUp
        case LIGHT_PEN_BUTTON_LEFT => controlPortB.emulateLeft
      }
    }
    override def mouseReleased(e:MouseEvent) : Unit = {
      lightPenButtonEmulation match {
        case LIGHT_PEN_NO_BUTTON =>
        case _ => controlPortB.releaseEmulated
      }
    }

    def init  : Unit = {}
    def reset  : Unit = {}
    // state
    protected def saveState(out:ObjectOutputStream) : Unit = {}
    protected def loadState(in:ObjectInputStream) : Unit = {}
    protected def allowsStateRestoring : Boolean = true
  }
  // ------------------------------------ Drag and Drop ----------------------------
  protected val DNDHandler = new DNDHandler(handleDND(_,true,true))

  protected object DriveLed8Listener extends AbstractDriveLedListener(driveLeds(0),diskProgressPanels(0))

  protected object DriveLed9Listener extends AbstractDriveLedListener(driveLeds(1),diskProgressPanels(1)) {
    driveLeds(1).setVisible(false)
  }
  // ------------------- INITIALIZATION ------------------------
  initComputer

  protected def initComputer : Unit = {
    ExpansionPort.setExpansionPortStateHandler(expansionPortStateHandler _)
  }
  // -----------------------------------------------------------

  protected def isC64Mode : Boolean = true

  def turnOn(args:Array[String]) : Unit
  def turnOff : Unit

  protected val tapeAllowed = true

  protected def getRAM : Memory
  protected def getCharROM : Memory

  protected def setDMA(dma:Boolean) : Unit

  protected def mainLoop(cycles:Long) : Unit

  protected def reset(play:Boolean=true) : Unit = {
    traceDialog.forceTracing(false)
    diskTraceDialog.forceTracing(false)
    if (Thread.currentThread != Clock.systemClock) clock.pause
    resetComponent
    if (play) clock.play
  }

  protected def savePrg : Unit

  protected def loadPRGFile(file:File,autorun:Boolean) : Unit

  protected def attachDiskFile(driveID:Int,file:File,autorun:Boolean,fileToLoad:Option[String],emulateInserting:Boolean = true) : Unit

  protected def saveSettings(save:Boolean) : Unit

  protected def enableDrive(id:Int,enabled:Boolean) : Unit

  protected def paste : Unit

  protected def setSettingsMenu(optionsMenu:JMenu) : Unit

  protected def setDisplayRendering(hints:java.lang.Object) : Unit

  protected def errorHandler(t:Throwable) : Unit = {
    import CPU65xx.CPUJammedException
    t match {
      case j:CPUJammedException if !cpujamContinue =>
        JOptionPane.showConfirmDialog(displayFrame,
          s"CPU[${j.cpuID}] jammed at " + Integer.toHexString(j.pcError) + ". Do you want to open debugger (yes), reset (no) or continue (cancel) ?",
          "CPU jammed",
          JOptionPane.YES_NO_CANCEL_OPTION,
          JOptionPane.ERROR_MESSAGE) match {
          case JOptionPane.YES_OPTION =>
            traceDialog.forceTracing(true)
            trace(true,true)
          case JOptionPane.CANCEL_OPTION => // continue
          case _ =>
            reset(true)
        }
      case _:CPUJammedException => // continue
      case _ =>
        Log.info("Fatal error occurred: " + cpu + "-" + t)
        try Log.info(CPU65xx.disassemble(mmu,cpu.getCurrentInstructionPC).toString) catch { case _:Throwable => }
        t.printStackTrace(Log.getOut)
        t.printStackTrace
        if (headless) {
          println(s"Fatal error occurred on cycle ${clock.currentCycles}: $cpu\n${CPU65xx.disassemble(mmu,cpu.getCurrentInstructionPC)}")
          t.printStackTrace
          sys.exit(1)
        } // exit if headless
        JOptionPane.showMessageDialog(displayFrame,t.toString + " [PC=" + Integer.toHexString(cpu.getCurrentInstructionPC) + "]", "Fatal error",JOptionPane.ERROR_MESSAGE)
        //trace(true,true)
        reset(true)
    }
  }

  protected def swing(f: => Unit) : Unit = SwingUtilities.invokeAndWait(() => f)

  protected def loadState(fileName:Option[String]) : Unit = {
    clock.pause
    var in : ObjectInputStream = null

    try {
      val canLoad = allowsState
      if (!canLoad) {

        showError("State saving error","Can't load state")
        return
      }
      val fn = fileName match {
        case None =>
          val fc = new JFileChooser
          fc.setCurrentDirectory(new File(configuration.getProperty(CONFIGURATION_LASTDISKDIR,"./")))
          fc.setFileFilter(new FileFilter {
            def accept(f: File) = f.isDirectory || f.getName.toUpperCase.endsWith(".K64")
            def getDescription = "Kernal64 state files"
          })
          fc.setDialogTitle("Choose a state file to load")
          fc.showOpenDialog(getActiveFrame.get) match {
            case JFileChooser.APPROVE_OPTION =>
              Some(fc.getSelectedFile)
            case _ =>
              None
          }
        case Some(fn) =>
          Some(new File(fn))
      }

      var loaded = false
      var asked = false
      var aborted = !fn.isDefined

      while (!loaded && !aborted) try {
        in = new ObjectInputStream(new GZIPInputStream(new FileInputStream(fn.get)))
        for(i <- 0 until APPLICATION_NAME.length) if (in.readChar != APPLICATION_NAME(i)) throw new IOException(s"Bad header: expected $APPLICATION_NAME")
        val ver = in.readObject.asInstanceOf[String]
        val ts = in.readLong
        if (!loadStateFromOptions && !asked) {
          val msg = s"<html><b>Version:</b> $ver<br><b>Date:</b> ${new java.util.Date(ts)}</html>"
          JOptionPane.showConfirmDialog(displayFrame, msg, "State loading confirmation", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE) match {
            case JOptionPane.YES_OPTION =>
              asked = true
              resetSettings
              reset(false)
              load(in)
              loaded = true
            case _ =>
              aborted = true
          }
        }
        else {
          reset(false)
          load(in)
          loaded = true
        }
      }
      catch {
        case dm: DriveIDMismatch =>
          in.close
          reset(false)
          val newDriveType = if (dm.expectedDriveComponentID.startsWith("C1541")) DriveType._1541
          else if (dm.expectedDriveComponentID.startsWith("D1571")) DriveType._1571
          else if (dm.expectedDriveComponentID.startsWith("D1581")) DriveType._1581
          else throw new IllegalArgumentException(s"Bad drive component type: ${dm.expectedDriveComponentID}")
          initDrive(dm.driveID,newDriveType)
      }
    }
    catch {
      case t:Throwable =>
        showError(s"State loading error",s"Can't load state: ${t.getMessage}")
        t.printStackTrace
        reset(false)
    }
    finally {
      if (in != null) in.close
      clock.play
    }
  }

  protected def resetSettings : Unit = {
    for(a <- resetSettingsActions) a()
  }

  protected def saveState() : Unit = {
    clock.pause
    var out : ObjectOutputStream = null
    try {
      val canSave = allowsState
      if (!canSave) {

        showError("State saving error","Can't save state")
        return
      }
      val fc = new JFileChooser
      fc.setDialogTitle("Choose where to save current state")
      fc.setCurrentDirectory(new File(configuration.getProperty(CONFIGURATION_LASTDISKDIR,"./")))
      fc.setFileFilter(new FileFilter {
        def accept(f: File) = f.isDirectory || f.getName.toUpperCase.endsWith(".K64")
        def getDescription = "Kernal64 state files"
      })
      val fn = fc.showSaveDialog(getActiveFrame.get) match {
        case JFileChooser.APPROVE_OPTION =>
          if (fc.getSelectedFile.getName.toUpperCase.endsWith(".K64")) fc.getSelectedFile.toString else fc.getSelectedFile.toString + ".k64"
        case _ =>
          return
      }
      out = new ObjectOutputStream(new GZIPOutputStream(new FileOutputStream(fn)))
      out.writeChars(APPLICATION_NAME)
      out.writeObject(ucesoft.cbm.Version.VERSION)
      out.writeLong(System.currentTimeMillis)
      save(out)
      out.close
    }
    catch {
      case t:Throwable =>

        showError("State saving error","Can't save state. Unexpected error occurred: " + t)
        t.printStackTrace
    }
    finally {
      if (out != null) out.close
      clock.play
    }
  }

  def expansionPortStateHandler(in:ObjectInputStream,portType:ExpansionPortType.Value) : Unit = {
    import ExpansionPortType._

    portType match {
      case CRT =>
        val crtFile = Cartridge.createCRTFileFromState(in)
        loadCartridgeFile(crtFile,true)
      case CPM =>
        settings.getLoadF[Boolean]("CPM64").foreach(_(true))
      case GEORAM =>
        settings.getLoadF[String]("GEO_RAM").foreach(_(in.readInt.toString))
      case REU =>
        settings.getLoadF[String]("REU_TYPE").foreach(_(in.readInt.toString))
      case DUALSID =>
        settings.getLoadF[String]("DUAL_SID").foreach(_(in.readObject.toString))
      case _ =>
    }
  }

  // ------------------------------- TRACE LISTENER ------------------------------------------
  def setTraceListener(tl:Option[TraceListener]) : Unit = {
    tl match {
      case None =>
        traceDialog.traceListener = cpu
      case Some(t) =>
        traceDialog.traceListener = t
    }
  }
  // ------------------------------------------------------------------------------------------
  protected def handleDND(file:File,_reset:Boolean,autorun:Boolean) : Unit = {
    val name = file.getName.toUpperCase
    if (name.endsWith(".CRT")) loadCartridgeFile(file)
    else {
      if (_reset) reset(false)
      if (autorun) {
        clock.schedule(new ClockEvent("Loading",clock.currentCycles + PRG_RUN_DELAY_CYCLES,(cycles) => { attachDevice(file,true,None,false) }))
        clock.play
      }
      else {
        attachDevice(file,false)
      }
    }
  }

  def attachDevice(file:File) : Unit = attachDevice(file,false)

  protected def attachDevice(file:File,autorun:Boolean,fileToLoad:Option[String] = None,emulateInserting:Boolean = true) : Unit = {
    val name = file.getName.toUpperCase

    if (name.endsWith(".PRG")) loadPRGFile(file,autorun)
    else
    if (name.endsWith(".D64") || name.endsWith(".G64") || name.endsWith(".D71") || name.endsWith(".D81")) attachDiskFile(0,file,autorun,fileToLoad,emulateInserting)
    else
    if (tapeAllowed && name.endsWith(".TAP")) attachTapeFile(file,autorun)
    else
    if (name.endsWith(".T64")) attachT64File(file,autorun)
    else
    if (name.endsWith(".ZIP")) attachZIPFile(file,autorun)
    else
    if (name.endsWith(".CRT")) loadCartridgeFile(file)
  }

  protected def printerSaveImage  : Unit = {
    val fc = new JFileChooser
    fc.showSaveDialog(printerDialog) match {
      case JFileChooser.APPROVE_OPTION =>
        val file = if (fc.getSelectedFile.getName.toUpperCase.endsWith(".PNG")) fc.getSelectedFile else new File(fc.getSelectedFile.toString + ".png")
        printerGraphicsDriver.saveAsPNG(file)
      case _ =>
    }
  }

  protected def attachZip  : Unit = {
    val fc = new JFileChooser
    fc.setCurrentDirectory(new File(configuration.getProperty(CONFIGURATION_LASTDISKDIR,"./")))
    fc.setFileFilter(new FileFilter {
      def accept(f: File) = f.isDirectory || f.getName.toUpperCase.endsWith(".ZIP")
      def getDescription = "ZIP files"
    })
    fc.showOpenDialog(displayFrame) match {
      case JFileChooser.APPROVE_OPTION =>
        attachZIPFile(fc.getSelectedFile,false)
      case _ =>
    }
  }

  protected def loadCartridgeFile(file:File,stateLoading : Boolean = false) : Unit = {
    try {
      if (!stateLoading && Thread.currentThread != Clock.systemClock) clock.pause
      ExpansionPort.getExpansionPort.eject
      val ep = ExpansionPortFactory.loadExpansionPort(file.toString,irqSwitcher.expPortIRQ _,nmiSwitcher.expansionPortNMI _,getRAM,configuration)
      println(ep)
      if (ep.isFreezeButtonSupported) cartMenu.setVisible(true)
      ExpansionPort.setExpansionPort(ep)
      ExpansionPort.currentCartFileName = file.toString
      Log.info(s"Attached cartridge ${ExpansionPort.getExpansionPort.name}")
      if (!stateLoading) reset(false)
      configuration.setProperty(CONFIGURATION_LASTDISKDIR,file.getParentFile.toString)
      detachCtrItem.setEnabled(true)
    }
    catch {
      case t:Throwable =>
        t.printStackTrace(traceDialog.logPanel.writer)

        showError("Cartridge loading error",t.toString)
    }
    finally {
      if (!stateLoading) clock.play
    }
  }

  protected def attachDisk(driveID:Int,autorun:Boolean,c64Mode:Boolean) : Unit = {
    val fc = new JFileChooser
    val canvas = new D64Canvas(fc,getCharROM,c64Mode)
    val sp = new javax.swing.JScrollPane(canvas)
    canvas.sp = sp
    fc.setAccessory(sp)
    fc.setFileView(new C64FileView)
    fc.setCurrentDirectory(new File(configuration.getProperty(CONFIGURATION_LASTDISKDIR,"./")))
    fc.setFileFilter(new javax.swing.filechooser.FileFilter {
      def accept(f:File) = f.isDirectory || drives(driveID).formatExtList.exists { ext => try { f.toString.toUpperCase.endsWith(ext) } catch { case _:Throwable=> false } }
      def getDescription = s"${drives(driveID).formatExtList.mkString(",")} files"
    })
    fc.showOpenDialog(displayFrame) match {
      case JFileChooser.APPROVE_OPTION =>
        attachDiskFile(driveID,fc.getSelectedFile,autorun,canvas.selectedFile,false)
      case _ =>
    }
  }

  protected def loadFileFromAttachedFile(driveID:Int,relocate:Boolean,c64Mode:Boolean) : Unit = {
    val floppy = drives(driveID).getFloppy
    if (floppy.isEmpty) showError("Loading error","No disk attached!")
    else {
      Option(JOptionPane.showInputDialog(displayFrame,"Load file","*")) match {
        case None =>
        case Some(fileName) =>
          try {
            floppy.asInstanceOf[Diskette].loadInMemory(mmu,fileName,relocate,true,driveID + 8)
          }
          catch {
            case t:Throwable =>

              showError("Loading error","Error while loading from disk: " + t.getMessage)
          }
      }
    }
  }

  protected def loadFileFromTape  : Unit = {
    val fc = new JFileChooser
    val canvas = new T64Canvas(fc,getCharROM,true)
    val sp = new javax.swing.JScrollPane(canvas)
    canvas.sp = sp
    fc.setAccessory(sp)
    fc.setFileView(new C64FileView)
    fc.setCurrentDirectory(new File(configuration.getProperty(CONFIGURATION_LASTDISKDIR,"./")))
    fc.setFileFilter(new FileFilter {
      def accept(f:File) = f.isDirectory || f.getName.toUpperCase.endsWith(".T64")
      def getDescription = "T64 files"
    })
    fc.showOpenDialog(displayFrame) match {
      case JFileChooser.APPROVE_OPTION =>
        attachT64File(new File(fc.getSelectedFile.toString),false)
      case _ =>
    }
  }

  protected def attachT64File(file:File,autorun:Boolean) : Unit = {
    val tape = new T64(file.toString)
    try {
      val values = tape.entries map { e => e.asInstanceOf[Object] }
      JOptionPane.showInputDialog(displayFrame,"Select file to open:","Open file in " + file,JOptionPane.INFORMATION_MESSAGE,null,values,values(0)) match {
        case null =>
        case entry:T64Entry =>
          tape.loadInMemory(mmu,entry)
          configuration.setProperty(CONFIGURATION_LASTDISKDIR,file.getParentFile.toString)
          if (autorun) {
            Keyboard.insertSmallTextIntoKeyboardBuffer("RUN" + 13.toChar,mmu,true)
          }
      }
    }
    finally {
      tape.close
    }
  }

  protected def attachZIPFile(file:File,autorun:Boolean) : Unit = {
    ZIP.zipEntries(file) match {
      case Success(entries) =>
        if (entries.size > 0) {
          val values = entries map { e => e.asInstanceOf[Object] } toArray

          JOptionPane.showInputDialog(displayFrame,"Select file to open:","Open file in " + file,JOptionPane.INFORMATION_MESSAGE,null,values,values(0)) match {
            case null =>
            case e@ZIP.ArchiveEntry(_,_,_) =>
              ZIP.extractEntry(e,new File(scala.util.Properties.tmpDir)) match {
                case Some(f) =>
                  attachDevice(f,autorun)
                case None =>
              }
          }
        }
      case Failure(t) =>

        showError(s"Error while opening zip file $file",t.toString)
    }
  }

  protected def attachTape  : Unit = {
    val fc = new JFileChooser
    fc.setFileView(new C64FileView)
    fc.setCurrentDirectory(new File(configuration.getProperty(CONFIGURATION_LASTDISKDIR,"./")))
    fc.setFileFilter(new FileFilter {
      def accept(f:File) = f.isDirectory || f.getName.toUpperCase.endsWith(".TAP")
      def getDescription = "TAP files"
    })
    fc.showOpenDialog(displayFrame) match {
      case JFileChooser.APPROVE_OPTION =>
        attachTapeFile(fc.getSelectedFile,false)
      case _ =>
    }
  }

  protected def attachTapeFile(file:File,autorun:Boolean) : Unit = {
    datassette.setTAP(Some(new TAP(file.toString)))
    tapeMenu.setEnabled(true)
    configuration.setProperty(CONFIGURATION_LASTDISKDIR,file.getParentFile.toString)
    if (autorun) {
      Keyboard.insertSmallTextIntoKeyboardBuffer("LOAD" + 13.toChar + "RUN" + 13.toChar,mmu,true)
    }
  }

  protected def loadPrg  : Unit = {
    val fc = new JFileChooser
    fc.setCurrentDirectory(new File(configuration.getProperty(CONFIGURATION_LASTDISKDIR,"./")))
    fc.setFileView(new C64FileView)
    fc.setFileFilter(new FileFilter {
      def accept(f: File) = f.isDirectory || f.getName.toUpperCase.endsWith(".PRG")
      def getDescription = "PRG files"
    })
    fc.showOpenDialog(displayFrame) match {
      case JFileChooser.APPROVE_OPTION =>
        loadPRGFile(fc.getSelectedFile,false)
      case _ =>
    }
  }

  protected def detachCtr  : Unit = {
    if (ExpansionPort.getExpansionPort.isEmpty) showError("Detach error","No cartridge attached!")
    else {
      if (Thread.currentThread != Clock.systemClock) clock.pause
      ExpansionPort.getExpansionPort.eject
      ExpansionPort.setExpansionPort(ExpansionPort.emptyExpansionPort)
      reset(true)
    }
    detachCtrItem.setEnabled(false)
    cartMenu.setVisible(false)
    ExpansionPort.currentCartFileName = ""
  }

  protected def attachCtr  : Unit = {
    val fc = new JFileChooser
    fc.setCurrentDirectory(new File(configuration.getProperty(CONFIGURATION_LASTDISKDIR,"./")))
    fc.setFileView(new C64FileView)
    fc.setFileFilter(new FileFilter {
      def accept(f:File) = f.isDirectory || f.getName.toUpperCase.endsWith(".CRT")
      def getDescription = "CRT files"
    })
    fc.showOpenDialog(displayFrame) match {
      case JFileChooser.APPROVE_OPTION =>
        loadCartridgeFile(fc.getSelectedFile)
      case _ =>
    }
  }

  protected def initDrive(id:Int,driveType:DriveType.Value) : Unit = {
    val old = Option(drives(id))
    old match {
      case Some(od) if od.driveType == driveType => return
      case _ =>
    }
    id match {
      case 0 =>
        drives(0) = driveType match {
          case DriveType._1571 =>
            DriveLed8Listener.setPowerLedMode(false)
            new D1571(0x00,bus,DriveLed8Listener,_ => {})
          case DriveType._1541 =>
            DriveLed8Listener.setPowerLedMode(false)
            new C1541(0x00,bus,DriveLed8Listener)
          case DriveType._1581 =>
            DriveLed8Listener.setPowerLedMode(true)
            new D1581(0x00,bus,DriveLed8Listener)
        }
      case 1 =>
        drives(1) = driveType match {
          case DriveType._1571 =>
            DriveLed9Listener.setPowerLedMode(false)
            new D1571(0x01,bus,DriveLed9Listener,_ => {})
          case DriveType._1541 =>
            DriveLed9Listener.setPowerLedMode(false)
            new C1541(0x01,bus,DriveLed9Listener)
          case DriveType._1581 =>
            DriveLed9Listener.setPowerLedMode(true)
            new D1581(0x01,bus,DriveLed9Listener)
        }
    }

    old match {
      case None =>
        add(drives(id))
      case Some(c) =>
        floppyComponents(id).drive = drives(id)
        c.getFloppy.close
        c.disconnect
        drivesRunning(id) = true
        drives(id).initComponent
        change(c,drives(id))
        inspectDialog.updateRoot
        if (id == 0) {
          diskTraceDialog.mem = drives(id).getMem
          diskTraceDialog.traceListener = drives(id)
        }
    }

    drives(id).runningListener = running => {
      drivesRunning(id) = running
    }
  }

  protected def loadKeyboard  : Unit = {
    JOptionPane.showConfirmDialog(displayFrame,"Would you like to set default keyboard or load a configuration from file ?","Keyboard layout selection", JOptionPane.YES_NO_CANCEL_OPTION,JOptionPane.QUESTION_MESSAGE) match {
      case JOptionPane.YES_OPTION =>
        configuration.remove(CONFIGURATION_KEYB_MAP_FILE)
        JOptionPane.showMessageDialog(displayFrame,"Reboot the emulator to activate the new keyboard", "Keyboard..",JOptionPane.INFORMATION_MESSAGE)
      case JOptionPane.NO_OPTION =>
        val fc = new JFileChooser
        fc.setCurrentDirectory(new File(configuration.getProperty(CONFIGURATION_LASTDISKDIR,"./")))
        fc.setDialogTitle("Choose a keyboard layout")
        fc.showOpenDialog(displayFrame) match {
          case JFileChooser.APPROVE_OPTION =>
            val in = new BufferedReader(new InputStreamReader(new FileInputStream(fc.getSelectedFile)))
            try {
              keyboard.KeyboardMapperStore.load(in)
              configuration.setProperty(CONFIGURATION_KEYB_MAP_FILE,fc.getSelectedFile.toString)
              JOptionPane.showMessageDialog(displayFrame,"Reboot the emulator to activate the new keyboard", "Keyboard..",JOptionPane.INFORMATION_MESSAGE)
            }
            catch {
              case _:IllegalArgumentException =>

                showError("Keyboard..","Invalid keyboard layout file")
            }
            finally {
              in.close
            }
          case _ =>
        }
      case JOptionPane.CANCEL_OPTION =>
    }
  }

  protected def writeOnDiskSetting(enabled:Boolean) : Unit = {
    canWriteOnDisk = enabled
    for(d <- 0 to 1) drives(d).getFloppy.canWriteOnDisk = canWriteOnDisk
  }

  protected def enableDrive10(enabled:Boolean,fn:Option[String]) : Unit = {
    if (enabled) {
      device10Drive = new LocalDrive(bus,10)
      changeLocalDriveDir(fn)
    }
    device10DriveEnabled = enabled
  }

  protected def changeLocalDriveDir(fileName:Option[String] = None) : Unit = {
    fileName match {
      case None =>
        val fc = new JFileChooser
        fc.setCurrentDirectory(device10Drive.asInstanceOf[LocalDrive].getCurrentDir)
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY)
        fc.setDialogTitle("Choose the current device 10 local directory")
        fc.showOpenDialog(displayFrame) match {
          case JFileChooser.APPROVE_OPTION =>
            device10Drive.asInstanceOf[LocalDrive].setCurrentDir(fc.getSelectedFile)
          case _ =>
        }
      case Some(fn) =>
        device10Drive.asInstanceOf[LocalDrive].setCurrentDir(new File(fn))
    }
  }

  protected def enableMouse(mouseEnabled:Boolean,display:vic.Display) : Unit = {
    controlPortA.setMouse1351Emulation(mouseEnabled)
    sid.setMouseEnabled(mouseEnabled)
    if (mouseEnabled) MouseCage.enableMouseCageOn(display) else MouseCage.disableMouseCage
  }

  protected def enablePrinter(enable:Boolean) : Unit = {
    printerEnabled = enable
    printer.setActive(enable)
  }

  protected def setDriveType(drive:Int,dt:DriveType.Value,dontPlay:Boolean = false) : Unit = {
    clock.pause
    initDrive(drive,dt)
    if (!dontPlay) clock.play
  }

  protected def setGeoRAM(enabled:Boolean,size:Int = 0): Unit = {
    if (enabled) ExpansionPort.setExpansionPort(new GeoRAM(size)) else ExpansionPort.setExpansionPort(ExpansionPort.emptyExpansionPort)
  }

  protected def setREU(reu:Option[Int],reu16FileName:Option[String]) : Unit = {
    reu match {
      case None =>
        ExpansionPort.getExpansionPort.eject
        ExpansionPort.setExpansionPort(ExpansionPort.emptyExpansionPort)
      case Some(REU.REU_16M) =>
        val reu = REU.getREU(REU.REU_16M,mmu,setDMA _,irqSwitcher.expPortIRQ _,reu16FileName map { new File(_) } )
        ExpansionPort.setExpansionPort(reu)
        reu16FileName match {
          case Some(file) => REU.attached16MFileName = file
          case None =>
        }
      case Some(reuSize) =>
        ExpansionPort.setExpansionPort(REU.getREU(reuSize,mmu,setDMA _,irqSwitcher.expPortIRQ _,None))
    }
  }

  protected def showKeyboardEditor(c64Mode:Boolean): Unit = {
    val source = configuration.getProperty(CONFIGURATION_KEYB_MAP_FILE,java.awt.im.InputContext.getInstance().getLocale.getLanguage.toUpperCase())
    val kbef = new JFrame(s"Keyboard editor ($source)")
    val kbe = new KeyboardEditor(keyb,keyb.getKeyboardMapper,c64Mode)
    kbef.getContentPane.add("Center",kbe)
    kbef.pack
    kbef.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE)
    kbef.setVisible(true)
  }

  protected def warpMode(warpOn:Boolean): Unit = {
    maxSpeedItem.setSelected(warpOn)
    clock.maximumSpeed = warpOn
    clock.pause
    sid.setFullSpeed(warpOn)
    clock.play
  }

  protected def setLightPen(setting:Int): Unit = {
    lightPenButtonEmulation = setting
    vicChip.enableLightPen(setting != LIGHT_PEN_NO_BUTTON)
    controlPortB.setLightPenEmulation(setting != LIGHT_PEN_NO_BUTTON)
  }

  protected def choose16MREU: Unit = {
    val fc = new JFileChooser
    fc.setCurrentDirectory(new File(configuration.getProperty(CONFIGURATION_LASTDISKDIR,"./")))
    fc.setFileView(new C64FileView)
    fc.setFileFilter(new javax.swing.filechooser.FileFilter {
      def accept(f: File) = f.isDirectory || f.getName.toUpperCase.endsWith(".REU")
      def getDescription = "REU files"
    })
    fc.showOpenDialog(displayFrame) match {
      case JFileChooser.APPROVE_OPTION|JFileChooser.CANCEL_OPTION =>
        try {
          setREU(Some(REU.REU_16M),if (fc.getSelectedFile == null) None else Some(fc.getSelectedFile.toString))
        }
        catch {
          case t:Throwable =>
            showError("REU loading error",t.toString)
        }
      case _ =>
    }
  }

  protected def setDualSID(address:Option[Int]): Unit = {
    DualSID.setDualSID(address,sid)
  }

  protected def setRemote(source:Option[JRadioButtonMenuItem]): Unit = {
    source match {
      case Some(source) =>
        if (remote.isDefined) remote.get.stopRemoting
        val listeningPort = JOptionPane.showInputDialog(displayFrame,"Listening port", "Remoting port configuration",JOptionPane.QUESTION_MESSAGE,null,null,"8064")
        if (listeningPort == null) {
          source.setSelected(false)
          display.setRemote(None)
        }
        else {
          try {
            val clip = display.getClipArea
            val remote = new ucesoft.cbm.remote.RemoteC64Server(listeningPort.toString.toInt,keyboardControlPort :: keyb :: keypadControlPort :: Nil,display.displayMem,vicChip.SCREEN_WIDTH,vicChip.SCREEN_HEIGHT,clip._1.x,clip._1.y,clip._2.x,clip._2.y)
            this.remote = Some(remote)
            display.setRemote(this.remote)
            remote.start
          }
          catch {
            case io:IOException =>

              showError("Remoting init error",io.toString)
              source.setSelected(false)
              display.setRemote(None)
          }
        }
      case None =>
        remote match {
          case Some(rem) =>
            rem.stopRemoting
            display.setRemote(None)
            remote = None
          case None =>
        }
    }
  }

  protected def enableFlyer(enabled:Boolean): Unit = {
    if (enabled != isFlyerEnabled) flyerIEC.reset
    isFlyerEnabled = enabled
  }

  protected def chooseFlyerDir: Unit = {
    val fc = new JFileChooser
    fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY)
    fc.setCurrentDirectory(flyerIEC.getFloppyRepository)
    fc.showOpenDialog(displayFrame) match {
      case JFileChooser.APPROVE_OPTION =>
        flyerIEC.setFloppyRepository(fc.getSelectedFile)
      case _ =>
    }
  }

  protected def setDigiMax(on:Boolean,address:Option[Int]): Unit = {
    if (on) {
      address match {
        case None =>
          DigiMAX.enabled(true,true)
        case Some(a) =>
          ExpansionPort.getExpansionPort.eject
          ExpansionPort.setExpansionPort(new DigiMaxCart(a))
      }
    }
    else {
      DigiMAX.enabled(false,false)
      if (ExpansionPort.getExpansionPort.isInstanceOf[DigiMaxCart]) ExpansionPort.setExpansionPort(ExpansionPort.emptyExpansionPort)
    }
  }

  protected def chooseDigiMaxSampleRate: Unit = {
    Option(JOptionPane.showInputDialog(displayFrame,"DigiMax sample rate Hz",DigiMAX.getSampleRate.toString)) match {
      case None =>
      case Some(rate) =>
        DigiMAX.setSampleRate(rate.toInt)
    }
  }

  protected def chooseGMod2: Unit = {
    var gmod2Path = configuration.getProperty(CONFIGURATION_GMOD2_FILE,"./gmod2_eeprom")
    val fc = new JFileChooser
    fc.setCurrentDirectory(new File(gmod2Path).getParentFile)
    fc.setDialogTitle("Choose a file where to save gmod2 cart eeprom")
    fc.showOpenDialog(displayFrame) match {
      case JFileChooser.APPROVE_OPTION =>
        gmod2Path = fc.getSelectedFile.toString
      case _ =>
    }
    configuration.setProperty(CONFIGURATION_GMOD2_FILE,gmod2Path)
  }

  protected def enableCPMCart(enabled:Boolean): Unit = {
    ExpansionPort.getExpansionPort.eject
    if (enabled) {
      ExpansionPort.setExpansionPort(new ucesoft.cbm.expansion.cpm.CPMCartridge(mmu,setDMA _,setTraceListener _))
      detachCtrItem.setEnabled(true)
    }
    else ExpansionPort.setExpansionPort(ExpansionPort.emptyExpansionPort)
  }

  protected def manageRS232 : Unit = {
    RS232ConfigPanel.RS232ConfigDialog.setVisible(true)
  }

  protected def makeDisk : Unit = {
    val fc = new JFileChooser
    fc.setCurrentDirectory(new File(configuration.getProperty(CONFIGURATION_LASTDISKDIR,"./")))
    fc.setFileView(new C64FileView)
    fc.setDialogTitle("Make an empty disk")
    fc.showSaveDialog(displayFrame) match {
      case JFileChooser.APPROVE_OPTION =>
        try {
          Diskette.makeEmptyDisk(fc.getSelectedFile.toString)
        }
        catch {
          case t:Throwable =>
            showError("Disk making error",t.toString)
        }
      case _ =>
    }
  }

  def play(file:File) = {
    //ExpansionPort.getExpansionPort.eject
    //ExpansionPort.setExpansionPort(ExpansionPort.emptyExpansionPort)
    handleDND(file,true,true)
  }

  protected def showAbout  : Unit = {
    val about = new AboutCanvas(getCharROM,ucesoft.cbm.Version.VERSION.toUpperCase + " (" + ucesoft.cbm.Version.BUILD_DATE.toUpperCase + ")")
    JOptionPane.showMessageDialog(displayFrame,about,"About",JOptionPane.INFORMATION_MESSAGE,new ImageIcon(getClass.getResource("/resources/commodore_file.png")))
  }

  protected def trace(cpu:Boolean,on:Boolean) : Unit = {
    if (cpu) {
      Log.setOutput(traceDialog.logPanel.writer)
      traceDialog.setVisible(on)
      traceItem.setSelected(on)
    }
    else {
      if (on) Log.setOutput(diskTraceDialog.logPanel.writer)
      else Log.setOutput(traceDialog.logPanel.writer)
      diskTraceDialog.setVisible(on)
      traceDiskItem.setSelected(on)
    }
  }

  protected def ejectDisk(driveID:Int) : Unit = {
    drives(driveID).getFloppy.close
    driveLeds(driveID).setToolTipText("")
    if (!traceDialog.isTracing) clock.pause
    if (drives(driveID).driveType == DriveType._1581) drives(driveID).setDriveReader(D1581.MFMEmptyFloppy,true)
    else drives(driveID).setDriveReader(EmptyFloppy,true)
    loadFileItems(driveID).setEnabled(false)
    clock.play
  }

  protected def showPrinterPreview : Unit = {
    printerGraphicsDriver.checkSize
    printerDialog.setVisible(true)
  }

  protected def configureJoystick : Unit = {
    def getControlPortFor(id:String) = configuration.getProperty(id) match {
      case CONFIGURATION_KEYPAD_VALUE => keypadControlPort
      case CONFIGURATION_JOYSTICK_VALUE => gameControlPort
      case CONFIGURATION_KEYBOARD_VALUE => keyboardControlPort
      case _ => controlport.ControlPort.emptyControlPort
    }

    controlPortA.controlPort = getControlPortFor(CONFIGURATION_JOY_PORT_2)
    controlPortB.controlPort = getControlPortFor(CONFIGURATION_JOY_PORT_1)
    keyboardControlPort.updateConfiguration
  }

  protected def joySettings : Unit = {
    Clock.systemClock.pause
    try {
      val dialog = new JoystickSettingDialog(displayFrame,configuration,gameControlPort)
      dialog.setVisible(true)
      configureJoystick
    }
    finally {
      Clock.systemClock.play
    }
  }

  protected def swapJoysticks : Unit = {
    val j1 = configuration.getProperty(CONFIGURATION_JOY_PORT_1)
    val j2 = configuration.getProperty(CONFIGURATION_JOY_PORT_2)
    if (j2 != null) configuration.setProperty(CONFIGURATION_JOY_PORT_1,j2) else configuration.remove(CONFIGURATION_JOY_PORT_1)
    if (j1 != null) configuration.setProperty(CONFIGURATION_JOY_PORT_2,j1) else configuration.remove(CONFIGURATION_JOY_PORT_2)
    configureJoystick
  }

  protected def setVicFullScreen: Unit = {
    ucesoft.cbm.misc.FullScreenMode.goFullScreen(displayFrame,
      display,
      vicChip.SCREEN_WIDTH,
      vicChip.SCREEN_HEIGHT,
      keypadControlPort,
      keyb,
      keypadControlPort,
      keyboardControlPort)
  }

  protected def openGIFRecorder : Unit = gifRecorder.setVisible(true)

  // -------------------------------------------------------------------
  protected def setMenu : Unit = {

    val menuBar = new JMenuBar
    val fileMenu = new JMenu("File")
    val editMenu = new JMenu("Edit")
    val stateMenu = new JMenu("State")
    val traceMenu = new JMenu("Trace")
    val optionMenu = new JMenu("Settings")
    val gamesMenu = new JMenu("Games")
    val helpMenu = new JMenu("Help")
    cartMenu.setVisible(false)

    menuBar.add(fileMenu)
    menuBar.add(editMenu)
    menuBar.add(stateMenu)
    menuBar.add(traceMenu)
    menuBar.add(optionMenu)
    menuBar.add(cartMenu)
    menuBar.add(gamesMenu)
    menuBar.add(helpMenu)

    setFileMenu(fileMenu)
    setEditMenu(editMenu)
    setStateMenu(stateMenu)
    setTraceMenu(traceMenu)
    setSettingsMenu(optionMenu)
    setGameMenu(gamesMenu)
    setHelpMenu(helpMenu)

    val cartButtonItem = new JMenuItem("Press cartridge button...")
    cartButtonItem.setAccelerator(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_Z,java.awt.event.InputEvent.ALT_DOWN_MASK))
    cartButtonItem.addActionListener(_ => cartButtonRequested = true )
    cartMenu.add(cartButtonItem)

    displayFrame.setJMenuBar(menuBar)

    setGlobalCommandLineOptions

  }

  protected def setGlobalCommandLineOptions : Unit = {
    // non-saveable settings
    settings.add("warp",
      "Run warp mode",
      (warp:Boolean) => if (warp) {
        clock.maximumSpeed = true
        maxSpeedItem.setSelected(true)
      }
    )
    settings.add("headless",
      "Run with no windows",
      (hl:Boolean) => if (hl) headless = true
    )
    settings.add("testcart",
      "Run with test cart",
      (tc:Boolean) => if (tc) TestCart.enabled = true
    )
    settings.add("limitcycles",
      "Run at most the number of cycles specified",
      (cycles:String) => if (cycles != "" && cycles.toLong > 0) clock.limitCyclesTo(cycles.toLong)
    )
    settings.add("run-file",
      "Run the given file taken from the attached disk",
      "RUNFILE",
      (runFile:String) => {},
      ""
    )
    settings.add("screenshot",
      "Take a screenshot of VIC screen and save it on the given file path. Used with --testcart only.",
      (file:String) => if (file != "") {
        TestCart.screenshotFile = Some(file)
        TestCart.screeshotHandler = display.saveSnapshot _
      }
    )
    settings.add("cpujam-continue",
      "On cpu jam continue execution",
      (jam:Boolean) => cpujamContinue = jam
    )
    settings.add("cia-model",
      "Set the CIA model (both cia1 and cia2). 6526 for old cia, 8521 for the new one. Default is 6526. ",
      (cm:String) => if (cm == "8521") {
        cia1.setCIAModel(CIA.CIA_MODEL_8521)
        cia2.setCIAModel(CIA.CIA_MODEL_8521)
      }
    )
    settings.add("load-state",
      "Load a previous saved state.",
      (file:String) => if (file != "") {
        try {
          loadStateFromOptions = true
          loadState(Some(file))
        }
        finally loadStateFromOptions = false
      }
    )
  }

  protected def setFileMenu(fileMenu:JMenu) : Unit = {
    val warpModeOnLoad = new JCheckBoxMenuItem("Warp mode on load")
    warpModeOnLoad.setSelected(true)
    warpModeOnLoad.addActionListener(e => ProgramLoader.loadingWithWarpEnabled = e.getSource.asInstanceOf[JCheckBoxMenuItem].isSelected )
    fileMenu.add(warpModeOnLoad)
    // Setting ---------------------------
    settings.add("warponload",
      "Enabled/disable warp mode on load",
      "WARPLOAD",
      (wpl:Boolean) => {
        ProgramLoader.loadingWithWarpEnabled = wpl
        warpModeOnLoad.setSelected(wpl)
      },
      warpModeOnLoad.isSelected
    )
    // -----------------------------------

    val zipItem = new JMenuItem("Attach zip ...")
    zipItem.addActionListener(_ => attachZip )
    fileMenu.add(zipItem)

    val tapeItem = new JMenuItem("Load file from tape ...")
    tapeItem.addActionListener(_ => loadFileFromTape )
    fileMenu.add(tapeItem)

    if (tapeAllowed) {
      val attachTapeItem = new JMenuItem("Attach tape ...")
      attachTapeItem.addActionListener(_ => attachTape)
      fileMenu.add(attachTapeItem)

      tapeMenu.setEnabled(false)
      fileMenu.add(tapeMenu)

      val tapePlayItem = new JMenuItem("Cassette press play")
      tapePlayItem.addActionListener(_ => datassette.pressPlay)
      tapeMenu.add(tapePlayItem)

      val tapeStopItem = new JMenuItem("Cassette press stop")
      tapeStopItem.addActionListener(_ => datassette.pressStop)
      tapeMenu.add(tapeStopItem)

      val tapeRecordItem = new JMenuItem("Cassette press record & play")
      tapeRecordItem.addActionListener(_ => datassette.pressRecordAndPlay)
      tapeMenu.add(tapeRecordItem)

      val tapeRewindItem = new JMenuItem("Cassette press rewind")
      tapeRewindItem.addActionListener(_ => datassette.pressRewind)
      tapeMenu.add(tapeRewindItem)
    }

    fileMenu.addSeparator

    val makeDiskItem = new JMenuItem("Make empty disk ...")
    makeDiskItem.addActionListener(_ => makeDisk )
    fileMenu.add(makeDiskItem)

    val autorunDiskItem = new JMenuItem("Autorun disk ...")
    autorunDiskItem.addActionListener(_ => attachDisk(0,true,true) )
    fileMenu.add(autorunDiskItem)

    val attachDisk0Item = new JMenuItem("Attach disk 8...")
    attachDisk0Item.setAccelerator(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_8,java.awt.event.InputEvent.ALT_DOWN_MASK))
    attachDisk0Item.addActionListener(_ => attachDisk(0,false,true) )
    fileMenu.add(attachDisk0Item)
    val attachDisk1Item = new JMenuItem("Attach disk 9...")
    attachDisk1Item.setAccelerator(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_9,java.awt.event.InputEvent.ALT_DOWN_MASK))
    attachDisk1Item.addActionListener(_ => attachDisk(1,false,true) )
    fileMenu.add(attachDisk1Item)
    // For settings see below, after drive type

    val ejectMenu = new JMenu("Eject disk")
    fileMenu.add(ejectMenu)
    val ejectDisk0Item = new JMenuItem("Eject disk 8...")
    ejectDisk0Item.addActionListener(_ => ejectDisk(0) )
    ejectMenu.add(ejectDisk0Item)
    val ejectDisk1Item = new JMenuItem("Eject disk 9...")
    ejectDisk1Item.addActionListener(_ => ejectDisk(1) )
    ejectMenu.add(ejectDisk1Item)

    loadFileItems(0).setEnabled(false)
    loadFileItems(0).addActionListener(_ => loadFileFromAttachedFile(0,true,true) )
    fileMenu.add(loadFileItems(0))
    loadFileItems(1).setEnabled(false)
    loadFileItems(1).addActionListener(_ => loadFileFromAttachedFile(1,true,true) )
    fileMenu.add(loadFileItems(1))
    fileMenu.addSeparator

    val loadPrgItem = new JMenuItem("Load PRG file from local disk ...")
    loadPrgItem.setAccelerator(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_G,java.awt.event.InputEvent.ALT_DOWN_MASK))
    loadPrgItem.addActionListener(_ => loadPrg )
    fileMenu.add(loadPrgItem)

    val savePrgItem = new JMenuItem("Save PRG file to local disk ...")
    savePrgItem.addActionListener(_ => savePrg )
    fileMenu.add(savePrgItem)

    // Setting ---------------------------
    settings.add("drive9-enabled",
      "Enabled/disable driver 9",
      "DRIVE_9_ENABLED",
      (d9e:Boolean) => {
        enableDrive(1,d9e)
      },
      drivesEnabled(1)
    )
    // -----------------------------------

    val localDriveItem = new JMenu("Drive on device 10 ...")
    fileMenu.add(localDriveItem)
    val group0 = new ButtonGroup
    val noLocalDriveItem = new JRadioButtonMenuItem("Disabled")
    noLocalDriveItem.setSelected(true)
    noLocalDriveItem.addActionListener(_ => enableDrive10(false,None) )
    group0.add(noLocalDriveItem)
    localDriveItem.add(noLocalDriveItem)
    val localDriveEnabled = new JRadioButtonMenuItem("Local drive ...")
    localDriveEnabled.addActionListener(_ => enableDrive10(true,None) )
    group0.add(localDriveEnabled)
    localDriveItem.add(localDriveEnabled)
    // Setting ---------------------------
    settings.add("drive10-local-path",
      "Enabled driver 10 to the given local path",
      "DRIVE_10_PATH",
      (d10p:String) => {
        val enabled = d10p != ""
        enableDrive10(enabled,if (enabled) Some(d10p) else None)
        localDriveEnabled.setSelected(enabled)
      },
      if (device10DriveEnabled) device10Drive.asInstanceOf[LocalDrive].getCurrentDir.toString
      else ""
    )
    // -----------------------------------

    val writeOnDiskCheckItem = new JCheckBoxMenuItem("Write changes on disk")
    writeOnDiskCheckItem.setSelected(true)
    writeOnDiskCheckItem.addActionListener(e => writeOnDiskSetting(e.getSource.asInstanceOf[JCheckBoxMenuItem].isSelected) )
    fileMenu.add(writeOnDiskCheckItem)
    // Setting ---------------------------
    settings.add("write-on-disk",
      "Tells if the modifications made on disks must be written on file",
      "WRITE_ON_DISK",
      (wod:Boolean) => {
        writeOnDiskCheckItem.setSelected(wod)
        writeOnDiskSetting(wod)
      },
      writeOnDiskCheckItem.isSelected
    )
    // -----------------------------------

    fileMenu.addSeparator

    val resetItem = new JMenuItem("Reset")
    resetItem.setAccelerator(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_R,java.awt.event.InputEvent.ALT_DOWN_MASK))
    resetItem.addActionListener(_ => reset(true) )
    fileMenu.add(resetItem)

    fileMenu.addSeparator

    val attachCtrItem = new JMenuItem("Attach cartridge ...")
    attachCtrItem.addActionListener(_ => attachCtr )
    fileMenu.add(attachCtrItem)
    // Setting ---------------------------
    settings.add("cart",
      "Attach the given cartridge",
      "ATTACH_CTR",
      (cart:String) => if (cart != "") loadCartridgeFile(new File(cart))
      ,
      ExpansionPort.currentCartFileName
    )
    // -----------------------------------

    detachCtrItem.setEnabled(false)
    detachCtrItem.addActionListener(_ => detachCtr )
    fileMenu.add(detachCtrItem)

    fileMenu.addSeparator

    val autoSaveCheckItem = new JCheckBoxMenuItem("Autosave settings on exit")
    autoSaveCheckItem.setSelected(configuration.getProperty(CONFIGURATION_AUTOSAVE,"false").toBoolean)
    autoSaveCheckItem.addActionListener(e => configuration.setProperty(CONFIGURATION_AUTOSAVE,e.getSource.asInstanceOf[JCheckBoxMenuItem].isSelected.toString) )
    fileMenu.add(autoSaveCheckItem)

    val saveSettingsCheckItem = new JMenuItem("Save settings")
    saveSettingsCheckItem.addActionListener(_ => saveSettings(true) )
    fileMenu.add(saveSettingsCheckItem)

    fileMenu.addSeparator

    val exitItem = new JMenuItem("Exit")
    exitItem.addActionListener(_ => turnOff )
    fileMenu.add(exitItem)
  }

  protected def setEditMenu(editMenu: JMenu) : Unit = {
    val pasteItem = new JMenuItem("Paste text")
    pasteItem.setAccelerator(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_V,java.awt.Event.CTRL_MASK))
    pasteItem.addActionListener(_ => paste )
    editMenu.add(pasteItem)
    val listItem = new JMenuItem("List BASIC to editor")
    listItem.setAccelerator(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_I,java.awt.Event.ALT_MASK))
    listItem.addActionListener(_ => ucesoft.cbm.misc.BasicListExplorer.list(mmu,0x801) )
    editMenu.add(listItem)
  }

  protected def setStateMenu(stateMenu: JMenu) : Unit = {
    val saveStateItem = new JMenuItem("Save state ...")
    saveStateItem.addActionListener(_ => saveState() )
    stateMenu.add(saveStateItem)
    val loadStateItem = new JMenuItem("Load state ...")
    loadStateItem.addActionListener(_ => loadState(None) )
    stateMenu.add(loadStateItem)
  }

  protected def setTraceMenu(traceMenu: JMenu) : Unit = {
    traceItem = new JCheckBoxMenuItem("Trace CPU")
    traceItem.setAccelerator(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_T,java.awt.event.InputEvent.ALT_DOWN_MASK))
    traceItem.setSelected(false)
    traceItem.addActionListener(e => trace(true,e.getSource.asInstanceOf[JCheckBoxMenuItem].isSelected) )
    traceMenu.add(traceItem)

    traceDiskItem = new JCheckBoxMenuItem("Trace Disk CPU")
    traceDiskItem.setSelected(false)
    traceDiskItem.addActionListener(e => trace(false,e.getSource.asInstanceOf[JCheckBoxMenuItem].isSelected) )
    traceMenu.add(traceDiskItem)

    val inspectItem = new JCheckBoxMenuItem("Inspect components ...")
    inspectItem.setSelected(false)
    inspectItem.addActionListener(e => inspectDialog.setVisible(e.getSource.asInstanceOf[JCheckBoxMenuItem].isSelected) )
    traceMenu.add(inspectItem)

    settings.add("trace",
      "Starts the emulator in trace mode",
      (trace:Boolean) => {
        this.traceOption = trace
        if (trace) {
          traceDialog.forceTracing(true)
          traceDialog.setVisible(true)
          traceItem.setSelected(true)
        }
      }
    )
  }

  protected def setGameMenu(gamesMenu: JMenu) : Unit = {
    val loader = ServiceLoader.load(classOf[ucesoft.cbm.game.GameProvider])
    var providers = loader.iterator
    try {
      if (!providers.hasNext) providers = java.util.Arrays.asList((new ucesoft.cbm.game.CSDBSpi).asInstanceOf[ucesoft.cbm.game.GameProvider],(new ucesoft.cbm.game.GameBaseSpi).asInstanceOf[ucesoft.cbm.game.GameProvider],(new ucesoft.cbm.game.PouetDemoSpi).asInstanceOf[ucesoft.cbm.game.GameProvider]).iterator
      val names = new collection.mutable.HashSet[String]
      while (providers.hasNext) {
        val provider = providers.next
        Log.info(s"Loaded ${provider.name} provider")
        if (!names.contains(provider.name)) {
          names += provider.name
          val item = new JCheckBoxMenuItem(provider.name)
          gamesMenu.add(item)
          item.addActionListener(new ActionListener {
            def actionPerformed(e:ActionEvent) : Unit = {
              try {
                val ui = GameUI.getUIFor(item,displayFrame,provider,cbmComputer)
                ui.setVisible(item.isSelected)
              }
              catch {
                case t:Throwable =>

                  showError(s"Error while contacting provider ${provider.name}'s server",t.toString)
              }
            }
          })
        }
      }
    }
    catch {
      case t:Throwable =>
        t.printStackTrace
    }
  }

  protected def setHelpMenu(helpMenu: JMenu) : Unit = {
    val aboutItem = new JMenuItem("About")
    aboutItem.addActionListener(_ => showAbout )
    helpMenu.add(aboutItem)
  }

  protected def setDriveMenu(parent:JMenu) : Unit = {
    val driveMenu = new JMenuItem("Drives ...")
    driveMenu.setAccelerator(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_L,java.awt.event.InputEvent.ALT_DOWN_MASK))
    parent.add(driveMenu)
    driveMenu.addActionListener(_ => DrivesConfigPanel.getDriveConfigDialog.setVisible(true) )
  }

  protected def setVolumeSettings(parent:JMenu) : Unit = {
    val volumeItem = new JMenuItem("Volume settings ...")
    volumeItem.setAccelerator(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_V,java.awt.event.InputEvent.ALT_DOWN_MASK))
    volumeItem.addActionListener(_ => volumeDialog.setVisible(true) )
    parent.add(volumeItem)
  }

  protected def setWarpModeSettings(parent:JMenu) : Unit = {
    maxSpeedItem.setAccelerator(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_W,java.awt.event.InputEvent.ALT_DOWN_MASK))
    maxSpeedItem.setSelected(clock.maximumSpeed)
    maxSpeedItem.addActionListener(e => warpMode(e.getSource.asInstanceOf[JCheckBoxMenuItem].isSelected) )
    parent.add(maxSpeedItem)
  }

  protected def setRenderingSettings(parent:JMenu) : Unit = {
    val renderingItem = new JMenu("Rendering")
    parent.add(renderingItem)
    val groupR = new ButtonGroup
    val renderingDefault1Item = new JRadioButtonMenuItem("Default")
    renderingDefault1Item.addActionListener(_ => setDisplayRendering(java.awt.RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR) )
    renderingItem.add(renderingDefault1Item)
    groupR.add(renderingDefault1Item)
    val renderingBilinear1Item = new JRadioButtonMenuItem("Bilinear")
    renderingBilinear1Item.addActionListener(_ => setDisplayRendering(java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR) )
    renderingItem.add(renderingBilinear1Item)
    groupR.add(renderingBilinear1Item)
    val renderingBicubic1Item = new JRadioButtonMenuItem("Bicubic")
    renderingBicubic1Item.addActionListener(_ => setDisplayRendering(java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC) )
    renderingItem.add(renderingBicubic1Item)
    groupR.add(renderingBicubic1Item)

    val paletteItem = new JMenu("Palette")
    parent.add(paletteItem)
    val groupP = new ButtonGroup
    val vicePalItem = new JRadioButtonMenuItem("VICE")
    vicePalItem.addActionListener(_ => Palette.setPalette(PaletteType.VICE) )
    paletteItem.add(vicePalItem)
    groupP.add(vicePalItem)
    val brightPalItem = new JRadioButtonMenuItem("Bright")
    brightPalItem.addActionListener(_ => Palette.setPalette(PaletteType.BRIGHT) )
    paletteItem.add(brightPalItem)
    groupP.add(brightPalItem)
    val peptoPalItem = new JRadioButtonMenuItem("Pepto")
    peptoPalItem.addActionListener(_ => Palette.setPalette(PaletteType.PEPTO) )
    paletteItem.add(peptoPalItem)
    groupP.add(peptoPalItem)
    // Setting ---------------------------
    settings.add("vic-palette",
      "Set the palette type (bright,vice,pepto)",
      "PALETTE",
      (dt:String) => {
        dt match {
          case "bright"|"" =>
            Palette.setPalette(PaletteType.BRIGHT)
            brightPalItem.setSelected(true)
          case "vice" =>
            Palette.setPalette(PaletteType.VICE)
            vicePalItem.setSelected(true)
          case "pepto" =>
            Palette.setPalette(PaletteType.PEPTO)
            peptoPalItem.setSelected(true)
          case _ =>
        }
      },
      if (brightPalItem.isSelected) "bright"
      else
        if (vicePalItem.isSelected) "vice"
        else
          if (peptoPalItem.isSelected) "pepto"
          else "bright"
    )
    settings.add("rendering-type",
      "Set the rendering type (default,bilinear,bicubic)",
      "RENDERING_TYPE",
      (dt:String) => {
        dt match {
          case "bilinear"|"" =>
            setDisplayRendering(java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            renderingBilinear1Item.setSelected(true)
          case "bicubic" =>
            setDisplayRendering(java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            renderingBicubic1Item.setSelected(true)
          case "default" =>
            setDisplayRendering(java.awt.RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
            renderingDefault1Item.setSelected(true)
          case _ =>
        }
      },
      if (renderingDefault1Item.isSelected) "default"
      else
        if (renderingBilinear1Item.isSelected) "bilinear"
        else
          if (renderingBicubic1Item.isSelected) "bicubic"
          else "default"
    )
  }

  protected def setFullScreenSettings(parent:JMenu) : Unit = {
    val fullScreenItem = new JMenuItem("Full screen")
    fullScreenItem.setAccelerator(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ENTER, java.awt.event.InputEvent.ALT_DOWN_MASK))
    fullScreenItem.addActionListener(_ => setVicFullScreen)
    parent.add(fullScreenItem)
  }

  protected def setJoysticsSettings(parent:JMenu) : Unit = {
    val joyAItem = new JMenuItem("Joystick...")
    joyAItem.addActionListener(_ => joySettings )
    parent.add(joyAItem)

    val swapJoyAItem = new JMenuItem("Swap joysticks")
    swapJoyAItem.addActionListener(_ => swapJoysticks )
    parent.add(swapJoyAItem)
  }

  protected def setLightPenSettings(parent:JMenu) : Unit = {
    val lightPenMenu = new JMenu("Light pen")
    parent.add(lightPenMenu)
    val group3 = new ButtonGroup
    val noPenItem = new JRadioButtonMenuItem("No light pen")
    noPenItem.setSelected(true)
    noPenItem.addActionListener(_ => setLightPen(LIGHT_PEN_NO_BUTTON) )
    // reset setting
    resetSettingsActions = (() => {
      noPenItem.setSelected(true)
      setLightPen(LIGHT_PEN_NO_BUTTON)
    }) :: resetSettingsActions
    group3.add(noPenItem)
    lightPenMenu.add(noPenItem)
    val penUp = new JRadioButtonMenuItem("Light pen with button up on control port 2")
    penUp.addActionListener(_ => setLightPen(LIGHT_PEN_BUTTON_UP) )
    group3.add(penUp)
    lightPenMenu.add(penUp)
    val penLeft = new JRadioButtonMenuItem("Light pen with button left on control port 2")
    penLeft.addActionListener(_ => setLightPen(LIGHT_PEN_BUTTON_LEFT) )
    group3.add(penLeft)
    lightPenMenu.add(penLeft)
  }

  protected def setMouseSettings(parent:JMenu) : Unit = {
    val mouseEnabledItem = new JCheckBoxMenuItem("Mouse 1351 enabled on port 1")
    mouseEnabledItem.setAccelerator(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_M,java.awt.event.InputEvent.ALT_DOWN_MASK))
    mouseEnabledItem.setSelected(false)
    mouseEnabledItem.addActionListener(e => enableMouse(e.getSource.asInstanceOf[JCheckBoxMenuItem].isSelected,display) )
    parent.add(mouseEnabledItem)
    // reset setting
    resetSettingsActions = (() => {
      mouseEnabledItem.setSelected(false)
      enableMouse(false,display)
    }) :: resetSettingsActions
  }

  protected def setPauseSettings(parent:JMenu) : Unit = {
    val pauseItem = new JCheckBoxMenuItem("Pause")
    pauseItem.setAccelerator(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_P,java.awt.event.InputEvent.ALT_DOWN_MASK))
    pauseItem.addActionListener(e =>
      if (e.getSource.asInstanceOf[JCheckBoxMenuItem].isSelected) {
        clock.pause
        display.setPaused
      } else clock.play )
    parent.add(pauseItem)
  }

  protected def setPrinterSettings(parent:JMenu) : Unit = {
    val printerPreviewItem = new JMenuItem("Printer preview ...")
    printerPreviewItem.addActionListener(_ => showPrinterPreview )
    parent.add(printerPreviewItem)

    val printerEnabledItem = new JCheckBoxMenuItem("Printer enabled")
    printerEnabledItem.setSelected(false)
    printerEnabledItem.addActionListener(e => { printerEnabled = e.getSource.asInstanceOf[JCheckBoxMenuItem].isSelected ; printer.setActive(printerEnabled) } )
    parent.add(printerEnabledItem)
    // Setting ---------------------------
    settings.add("printer-enabled",
      "Enable printer",
      "PRINTER_ENABLED",
      (pe:Boolean) => {
        enablePrinter(pe)
        printerEnabledItem.setSelected(pe)
      },
      printerEnabledItem.isSelected
    )
    // reset setting
    resetSettingsActions = (() => {
      printerEnabledItem.setSelected(false)
      printerEnabled = false
      printer.setActive(false)
    }) :: resetSettingsActions
  }

  protected def setSIDSettings(parent:JMenu) : Unit = {
    val sidItem = new JMenu("SID")
    parent.add(sidItem)
    val group7 = new ButtonGroup
    val sidTypeItem = new JMenu("SID Type")
    sidItem.add(sidTypeItem)
    val sid6581Item = new JRadioButtonMenuItem("MOS 6581")
    sid6581Item.setSelected(true)
    sid6581Item.addActionListener(_ => sid.setModel(true) )
    sidTypeItem.add(sid6581Item)
    group7.add(sid6581Item)
    val sid8580Item = new JRadioButtonMenuItem("MOS 8580")
    sid8580Item.setSelected(false)
    sid8580Item.addActionListener(_ => sid.setModel(false) )
    sidTypeItem.add(sid8580Item)
    group7.add(sid8580Item)
    // Setting ---------------------------
    settings.add("sid-8580",
      "Enable sid 8580 type",
      "SID_8580",
      (sid8580:Boolean) => {
        sid.setModel(!sid8580)
        sid8580Item.setSelected(sid8580)
      },
      sid8580Item.isSelected
    )
    val sid2Item = new JMenu("Dual SID")
    sidItem.add(sid2Item)
    val group8 = new ButtonGroup
    val nosid2Item = new JRadioButtonMenuItem("None")
    sid2Item.add(nosid2Item)
    nosid2Item.setSelected(true)
    nosid2Item.addActionListener(_ => setDualSID(None) )
    group8.add(nosid2Item)
    val addressMap = (for(adr <- DualSID.validAddresses(isC64Mode)) yield {
      val sid2AdrItem = new JRadioButtonMenuItem(adr)
      sid2Item.add(sid2AdrItem)
      sid2AdrItem.setSelected(false)
      sid2AdrItem.addActionListener(_ => setDualSID(Some(Integer.parseInt(adr,16))) )
      group8.add(sid2AdrItem)
      adr -> sid2AdrItem
    }).toMap
    // Setting ---------------------------
    settings.add("dual-sid",
      s"Enable dual sid on the given address. Valid addresses are: ${addressMap.keys.mkString(",")}",
      "DUAL_SID",
      (adr:String) => if (adr != null && adr != "") {
        addressMap get adr match {
          case Some(item) =>
            item.setSelected(true)
            setDualSID(Some(Integer.parseInt(adr,16)))
          case None =>
            throw new IllegalArgumentException(s"Invalid dual sid address: $adr")
        }
      },
      if (nosid2Item.isSelected) ""
      else {
        addressMap filter { kv => kv._2.isSelected } headOption match {
          case Some((a,_)) => a
          case None => ""
        }
      }
    )
    val sidCycleExactItem = new JCheckBoxMenuItem("SID cycle exact emulation")
    sidCycleExactItem.setSelected(false)
    sidCycleExactItem.addActionListener(_ => {
      sidCycleExact = sidCycleExactItem.isSelected
      sid.setCycleExact(sidCycleExactItem.isSelected)
    }
    )
    sidItem.add(sidCycleExactItem)
    // Setting ---------------------------
    settings.add("sid-cycle-exact",
      "With this option enabled the SID emulation is more accurate with a decrease of performance",
      "SID_CYCLE_EXACT",
      (sce:Boolean) => {
        clock.pause
        sidCycleExact = sce
        sid.setCycleExact(sidCycleExact)
        sidCycleExactItem.setSelected(sidCycleExact)
        clock.play
      },
      sidCycleExact
    )
    // reset setting
    resetSettingsActions = (() => {
      sid6581Item.setSelected(true)
      sid.setModel(true)
      nosid2Item.setSelected(true)
      setDualSID(None)
    }) :: resetSettingsActions
  }

  protected def setDrivesSettings : Unit = {
    for(drive <- 0 to 1) {
      // Setting ---------------------------
      settings.add(s"drive${drive + 8}-type",
        "Set the driver's type (1541,1571,1581)",
        s"DRIVER${drive + 8}_TYPE",
        (dt: String) => {
          dt match {
            case "1541" =>
              setDriveType(drive,DriveType._1541, true)
            case "1571" =>
              setDriveType(drive,DriveType._1571, true)
            case "1581" =>
              setDriveType(drive,DriveType._1581, true)
            case _ =>
          }
        },
        if (drives(drive).driveType == DriveType._1541) "1541"
        else if (drives(drive).driveType == DriveType._1571) "1571"
        else if (drives(drive).driveType == DriveType._1581) "1581"
        else "1571"
      )

      settings.add(s"drive${drive + 8}-file",
        s"Attach a file to drive ${drive + 8}",
        s"DRIVE_${drive + 8}_FILE",
        (df: String) => {
          if (df != "") attachDiskFile(drive, new File(df), false, None)
        },
        floppyComponents(drive).drive.getFloppy.file
      )
    }
  }

  protected def setRemotingSettings(parent:JMenu) : Unit = {
    val remoteItem = new JMenu("Remoting")
    parent.add(remoteItem)

    val group10 = new ButtonGroup
    val remoteDisabledItem = new JRadioButtonMenuItem("Off")
    remoteDisabledItem.setSelected(true)
    remoteDisabledItem.addActionListener(_ => setRemote(None) )
    group10.add(remoteDisabledItem)
    remoteItem.add(remoteDisabledItem)
    val remoteEnabledItem = new JRadioButtonMenuItem("On ...")
    remoteEnabledItem.addActionListener(e => setRemote(Some(e.getSource.asInstanceOf[JRadioButtonMenuItem])) )
    group10.add(remoteEnabledItem)
    remoteItem.add(remoteEnabledItem)
  }

  protected def setREUSettings(parent:JMenu) : Unit = {
    val reuItem = new JMenu("REU")
    val group5 = new ButtonGroup
    val noReuItem = new JRadioButtonMenuItem("None")
    noReuItem.setSelected(true)
    noReuItem.addActionListener(_ => setREU(None,None) )
    group5.add(noReuItem)
    reuItem.add(noReuItem)
    val reu128Item = new JRadioButtonMenuItem("128K")
    reu128Item.addActionListener(_ => setREU(Some(REU.REU_1700),None) )
    group5.add(reu128Item)
    reuItem.add(reu128Item)
    val reu256Item = new JRadioButtonMenuItem("256K")
    reu256Item.addActionListener(_ => setREU(Some(REU.REU_1764),None) )
    group5.add(reu256Item)
    reuItem.add(reu256Item)
    val reu512Item = new JRadioButtonMenuItem("512K")
    reu512Item.addActionListener(_ => setREU(Some(REU.REU_1750),None) )
    group5.add(reu512Item)
    reuItem.add(reu512Item)
    val reu16MItem = new JRadioButtonMenuItem("16M ...")
    reu16MItem.addActionListener(_ => choose16MREU )
    group5.add(reu16MItem)
    reuItem.add(reu16MItem)
    parent.add(reuItem)

    settings.add("reu-type",
      "Set the reu type (none,128,256,512,16384)",
      "REU_TYPE",
      (reu:String) => {
        val reuPars = reu.split(",")
        if (reuPars(0) == "" || reuPars(0) == "none") setREU(None,None)
        else
          reuPars(0).toInt match {
            case REU.REU_1700 =>
              setREU(Some(REU.REU_1700),None)
              reu128Item.setSelected(true)
            case REU.REU_1750 =>
              setREU(Some(REU.REU_1750),None)
              reu512Item.setSelected(true)
            case REU.REU_1764 =>
              setREU(Some(REU.REU_1764),None)
              reu256Item.setSelected(true)
            case REU.REU_16M =>
              setREU(Some(REU.REU_16M),if (reuPars.length == 2 && reuPars(1) != "null") Some(reuPars(1)) else None)
              reu16MItem.setSelected(true)
          }
      },
      if (noReuItem.isSelected) "none"
      else
        if (reu128Item.isSelected) "128"
        else
          if (reu256Item.isSelected) "256"
          else
            if (reu512Item.isSelected) "512"
            else
              if (reu16MItem.isSelected) "16384," + REU.attached16MFileName
              else "none"
    )
    // reset setting
    resetSettingsActions = (() => {
      noReuItem.setSelected(true)
      setREU(None,None)
    }) :: resetSettingsActions
  }

  protected def setGEORamSettings(parent:JMenu) : Unit = {
    val geoItem = new JMenu("GeoRAM")
    val groupgeo = new ButtonGroup
    val noGeoItem = new JRadioButtonMenuItem("None")
    noGeoItem.setSelected(true)
    noGeoItem.addActionListener(_ => setGeoRAM(false) )
    groupgeo.add(noGeoItem)
    geoItem.add(noGeoItem)
    val _256kGeoItem = new JRadioButtonMenuItem("256K")
    _256kGeoItem.addActionListener(_ => setGeoRAM(true,256) )
    groupgeo.add(_256kGeoItem)
    geoItem.add(_256kGeoItem)
    val _512kGeoItem = new JRadioButtonMenuItem("512K")
    _512kGeoItem.addActionListener(_ => setGeoRAM(true,512) )
    groupgeo.add(_512kGeoItem)
    geoItem.add(_512kGeoItem)

    parent.add(geoItem)
    // Setting ---------------------------
    settings.add("geo-ram",
      "Set the georam size (none,256,512)",
      "GEO_RAM",
      (geo:String) => {
        if (geo == "512") {
          _512kGeoItem.setSelected(true)
          setGeoRAM(true,512)
        }
        else
          if (geo == "256") {
            _256kGeoItem.setSelected(true)
            setGeoRAM(true,256)
          }
      },
      if (_512kGeoItem.isSelected) "512"
      else
        if (_256kGeoItem.isSelected) "256"
        else "none"
    )
    // reset setting
    resetSettingsActions = (() => {
      noGeoItem.setSelected(true)
      setGeoRAM(false)
    }) :: resetSettingsActions
  }

  protected def setDigiMAXSettings(parent:JMenu) : Unit = {
    val digimaxItem = new JMenu("DigiMAX")
    parent.add(digimaxItem)
    val digiMaxSampleRateItem  = new JMenuItem("DigiMax sample rate ...")
    digiMaxSampleRateItem.addActionListener(_ => chooseDigiMaxSampleRate )
    digimaxItem.add(digiMaxSampleRateItem)
    val group6 = new ButtonGroup
    val digimaxDisabledItem = new JRadioButtonMenuItem("Disabled")
    digimaxDisabledItem.setSelected(true)
    digimaxDisabledItem.addActionListener(_ => setDigiMax(false,None) )
    digimaxItem.add(digimaxDisabledItem)
    group6.add(digimaxDisabledItem)
    val digimaxOnUserPortItem = new JRadioButtonMenuItem("On UserPort")
    digimaxOnUserPortItem.addActionListener(_ => setDigiMax(true,None) )
    group6.add(digimaxOnUserPortItem)
    digimaxItem.add(digimaxOnUserPortItem)
    val digimaxDE00Item = new JRadioButtonMenuItem("On DE00")
    digimaxDE00Item.addActionListener(_ => setDigiMax(true,Some(0xDE00)) )
    group6.add(digimaxDE00Item)
    digimaxItem.add(digimaxDE00Item)
    val digimaxDF00Item = new JRadioButtonMenuItem("On DF00")
    digimaxDF00Item.addActionListener(_ => setDigiMax(true,Some(0xDF00)) )
    group6.add(digimaxDF00Item)
    digimaxItem.add(digimaxDF00Item)
    // reset setting
    resetSettingsActions = (() => {
      digimaxDisabledItem.setSelected(true)
      setDigiMax(false,None)
    }) :: resetSettingsActions
  }

  protected def setCPMSettings(parent:JMenu) : Unit = {
    val cpmItem = new JCheckBoxMenuItem("CP/M Cartridge")
    cpmItem.addActionListener(e => enableCPMCart(e.getSource.asInstanceOf[JCheckBoxMenuItem].isSelected) )
    parent.add(cpmItem)
    settings.add("cpm64-enabled",
      s"Attach the CP/M cart",
      "CPM64",
      (cpm: Boolean) => {
        if (cpm) {
          enableCPMCart(true)
          cpmItem.setSelected(true)
        }
      },
      ExpansionPort.getExpansionPort.isInstanceOf[CPMCartridge]
    )
    // reset setting
    resetSettingsActions = (() => {
      cpmItem.setSelected(false)
      enableCPMCart(false)
    }) :: resetSettingsActions
  }

  protected def setFlyerSettings(parent:JMenu) : Unit = {
    val flyerItem = new JMenu("Flyer internet modem")
    parent.add(flyerItem)
    val fylerEnabledItem = new JCheckBoxMenuItem("Flyer enabled on 7")
    fylerEnabledItem.setSelected(false)
    flyerItem.add(fylerEnabledItem)
    fylerEnabledItem.addActionListener(e => enableFlyer(e.getSource.asInstanceOf[JCheckBoxMenuItem].isSelected) )
    val flyerDirectoryItem = new JMenuItem("Set disks repository ...")
    flyerItem.add(flyerDirectoryItem)
    flyerDirectoryItem.addActionListener(_ => chooseFlyerDir )
    // reset setting
    resetSettingsActions = (() => {
      fylerEnabledItem.setSelected(false)
      enableFlyer(false)
    }) :: resetSettingsActions
  }
}
