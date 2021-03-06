package ucesoft.cbm.c64

import java.awt.datatransfer.DataFlavor
import java.awt.{Toolkit, _}
import java.io._

import javax.swing._
import javax.swing.filechooser.FileFilter
import ucesoft.cbm._
import ucesoft.cbm.expansion._
import ucesoft.cbm.expansion.cpm.CPMCartridge
import ucesoft.cbm.formats._
import ucesoft.cbm.misc._
import ucesoft.cbm.peripheral._
import ucesoft.cbm.peripheral.bus.BusSnoop
import ucesoft.cbm.peripheral.c2n.Datassette
import ucesoft.cbm.peripheral.drive._
import ucesoft.cbm.peripheral.keyboard.Keyboard
import ucesoft.cbm.peripheral.vic.Palette
import ucesoft.cbm.peripheral.vic.Palette.PaletteType
import ucesoft.cbm.trace.{InspectPanel, TraceDialog}

object C64 extends App {
  CBMComputer.turnOn(new C64,args)
}

class C64 extends CBMComputer {
  val componentID = "Commodore 64"
  val componentType = CBMComponentType.INTERNAL

  protected val APPLICATION_NAME = "Kernal64"
  protected val CONFIGURATION_FILENAME = "C64.config"

  protected val keybMapper : keyboard.KeyboardMapper = keyboard.KeyboardMapperStore.loadMapper(Option(configuration.getProperty(CONFIGURATION_KEYB_MAP_FILE)),"/resources/default_keyboard_c64")

  protected val mmu = new C64MMU.MAIN_MEMORY
  protected val busSnooper = new BusSnoop(bus)
  protected var busSnooperActive = false
  protected val c1541 = new C1541Emu(bus,DriveLed8Listener)

  def reset  : Unit = {
    dma = false
    clock.maximumSpeed = false
    maxSpeedItem.setSelected(false)
    ProgramLoader.reset
    cia12Running(0) = true
    cia12Running(1) = true
  }
  
  def init  : Unit = {
    val sw = new StringWriter
    Log.setOutput(new PrintWriter(sw))
    Log.setInfo
    
    Log.info("Building the system ...")
    RS232ConfigPanel.registerAvailableRS232Drivers(displayFrame,AVAILABLE_RS232)
    ExpansionPort.addConfigurationListener(mmu)
    // drive
    initDrive(0,DriveType._1541)
    initDrive(1,DriveType._1541)
    drivesEnabled(1) = false
    // -----------------------
    ProgramLoader.cpu = cpu
    ProgramLoader.warpModeListener = warpMode _
    add(clock)
    add(mmu)
    add(cpu)
    add(keyb)
    add(controlPortA)
    add(controlPortB)
    add(bus)
    add(expansionPort)
    add(rs232)    
    floppyComponents(0) = new FloppyComponent(8,drives(0),driveLeds(0))
    add(floppyComponents(0))
    floppyComponents(1) = new FloppyComponent(9,drives(1),driveLeds(1))
    add(floppyComponents(1))
    // -----------------------
    val vicMemory = new C64VICMemory(mmu,mmu.CHAR_ROM,cpu)
    add(vicMemory)
    ExpansionPort.setMemoryForEmptyExpansionPort(vicMemory)
    ExpansionPort.addConfigurationListener(vicMemory)    
    import cia._
    // control ports
    val cia1CP1 = new CIA1Connectors.PortAConnector(keyb,controlPortA)
    val cia1CP2 = new CIA1Connectors.PortBConnector(keyb,controlPortB,() => vicChip.triggerLightPen)
    add(cia1CP1)
    add(cia1CP2)
    add(irqSwitcher)    
    // CIAs
    cia1 = new CIA("CIA1",
    				   0xDC00,
    				   cia1CP1,
    				   cia1CP2,
    				   irqSwitcher.ciaIRQ _,
               idle => cia12Running(0) = !idle)
    val cia2CP1 = new CIA2Connectors.PortAConnector(vicMemory,bus,rs232)
    val cia2CP2 = new CIA2Connectors.PortBConnector(rs232)    
    add(cia2CP1)
    add(cia2CP2)
    add(nmiSwitcher)    
    cia2 = new CIA("CIA2",
    				   0xDD00,
    				   cia2CP1,
    				   cia2CP2,
    				   nmiSwitcher.cia2NMIAction _,
               idle => cia12Running(1) = !idle)
    rs232.setCIA12(cia1,cia2)
    ParallelCable.ca2Callback = cia2.setFlagLow _
    add(ParallelCable)
    vicChip = new vic.VIC(vicMemory,mmu.COLOR_RAM,irqSwitcher.vicIRQ _,baLow _)      
    mmu.setLastByteReadMemory(vicMemory)
    // mapping I/O chips in memory
    mmu.setIO(cia1,cia2,sid,vicChip)
    display = new vic.Display(vicChip.SCREEN_WIDTH,vicChip.SCREEN_HEIGHT,displayFrame.getTitle,displayFrame)
    add(display)
    display.setPreferredSize(new java.awt.Dimension(vicChip.VISIBLE_SCREEN_WIDTH,vicChip.VISIBLE_SCREEN_HEIGHT))
    vicChip.setDisplay(display)
    displayFrame.getContentPane.add("Center",display)
    displayFrame.addKeyListener(keyb)
    displayFrame.addKeyListener(keypadControlPort)
    displayFrame.addKeyListener(keyboardControlPort)
    display.addMouseListener(keypadControlPort)
    display.addMouseListener(controlport.ControlPort.emptyControlPort)
    val lightPen = new LightPenButtonListener
    add(lightPen)
    display.addMouseListener(lightPen)
    traceDialog = TraceDialog.getTraceDialog(displayFrame,mmu,cpu,display,vicChip)
    diskTraceDialog = TraceDialog.getTraceDialog(displayFrame,drives(0).getMem,drives(0))
    // drive leds
    add(driveLeds(0))        
    add(driveLeds(1))
    configureJoystick
    add(c1541)
    Log.setOutput(traceDialog.logPanel.writer)
    // tape
    datassette = new Datassette(cia1.setFlagLow _)
    mmu.setDatassette(datassette)
    add(datassette)
    // printer
    add(printer)
    // Flyer
    add(flyerIEC)
    
    // info panel
    val infoPanel = new JPanel(new BorderLayout)
    val rowPanel = new JPanel(new BorderLayout(0,0))
    val row1Panel = new JPanel(new FlowLayout(FlowLayout.RIGHT))
    val row2Panel = new JPanel(new FlowLayout(FlowLayout.RIGHT))
    rowPanel.add("North",row1Panel)
    rowPanel.add("South",row2Panel)
    val tapePanel = new TapeState
    datassette.setTapeListener(tapePanel)
    row1Panel.add(tapePanel)
    row1Panel.add(tapePanel.progressBar)
    row1Panel.add(diskProgressPanels(0))
    row1Panel.add(driveLeds(0))
    row2Panel.add(diskProgressPanels(1))
    row2Panel.add(driveLeds(1))
    infoPanel.add("East",rowPanel)
    displayFrame.getContentPane.add("South",infoPanel)
    displayFrame.setTransferHandler(DNDHandler)
    Log.info(sw.toString)

    // GIF Recorder
    gifRecorder = GIFPanel.createGIFPanel(displayFrame,Array(display),Array("VIC"))
  }

  private def loadSettings(args:Array[String]) : Unit = {
    settings.load(configuration)
    // AUTOPLAY
    settings.parseAndLoad(args) match {
      case None =>
        // run the given file name
        settings.get[String]("RUNFILE") match {
          case None =>
          case Some(fn) =>
            val cmd = s"""LOAD"$fn",8,1""" + 13.toChar + "RUN" + 13.toChar
            clock.schedule(new ClockEvent("Loading",clock.currentCycles + 2200000,(cycles) => Keyboard.insertTextIntoKeyboardBuffer(cmd,mmu,true) ))
        }
      case Some(f) =>
        handleDND(new File(f),false,true)
    }
    DrivesConfigPanel.registerDrives(displayFrame,drives,setDriveType(_,_,false),enableDrive _,attachDisk(_,_,true),attachDiskFile(_,_,_,None),drivesEnabled)
  }
  
  override def afterInitHook  : Unit = {
	  inspectDialog = InspectPanel.getInspectDialog(displayFrame,this)    
    // deactivate drive 9
    drives(1).setActive(false)    
    driveLeds(1).setVisible(false)
  }
  
  protected def mainLoop(cycles:Long) : Unit = {
    // VIC PHI1
    vicChip.clock
    // CIAs
    if (cia12Running(0)) cia1.clock(false)
    if (cia12Running(1)) cia2.clock(false)
    //DRIVES
    var d = 0
    while (d < 2) {
      if (drivesEnabled(d) && drivesRunning(d)) drives(d).clock(cycles)

      d += 1
    }
    if (device10DriveEnabled) device10Drive.clock(cycles)
    // bus snoop
    if (busSnooperActive) busSnooper.clock(cycles)
    // printer
    if (printerEnabled) printer.clock(cycles)
    // Flyer
    if (isFlyerEnabled) flyerIEC.clock(cycles)
    // check cart freezing button
    if (cartButtonRequested && cpu.isFetchingInstruction) {
      cartButtonRequested = false
      ExpansionPort.getExpansionPort.freezeButton
    }
    // CPU PHI2
    ProgramLoader.checkLoadingInWarpMode(true)
    cpu.fetchAndExecute(1)
    // SID
    if (sidCycleExact) sid.clock
  }

  protected def setDMA(dma:Boolean) : Unit = {
    this.dma = dma
    cpu.setDMA(dma)
  }
  
  private def baLow(low:Boolean) : Unit = {
    cpu.setBaLow(low)
    expansionPort.setBaLow(low)
  }

  override def isHeadless = headless

  // ======================================== Settings ==============================================
  protected def enableDrive(id:Int,enabled:Boolean) : Unit = {
    drivesEnabled(id) = enabled
    drives(id).setActive(enabled)
    driveLeds(id).setVisible(enabled)
    adjustRatio
  }

  protected def setDisplayRendering(hints:java.lang.Object) : Unit = {
    display.setRenderingHints(hints)
  }


  
  private def adjustRatio  : Unit = {
    val dim = display.asInstanceOf[java.awt.Component].getSize
    dim.height = (dim.width / vicChip.SCREEN_ASPECT_RATIO).round.toInt
    display.setPreferredSize(dim) 
    displayFrame.pack
  } 

  protected def loadPRGFile(file:File,autorun:Boolean) : Unit = {
    val (start,end) = ProgramLoader.loadPRG(mmu,file,true,8)
    Log.info(s"BASIC program loaded from $start to $end")
    configuration.setProperty(CONFIGURATION_LASTDISKDIR,file.getParentFile.toString)
    if (autorun) {
      Keyboard.insertSmallTextIntoKeyboardBuffer("RUN" + 13.toChar,mmu,true)
    }
  }

  protected def attachDiskFile(driveID:Int,file:File,autorun:Boolean,fileToLoad:Option[String],emulateInserting:Boolean = true) : Unit = {
    try {   
      if (!file.exists) throw new FileNotFoundException(s"Cannot attach file $file on drive ${driveID + 8}: file not found")
      val validExt = drives(driveID).formatExtList.exists { ext => file.toString.toUpperCase.endsWith(ext) }
      if (!validExt) throw new IllegalArgumentException(s"$file cannot be attached to disk, format not valid")
      val isD64 = file.getName.toUpperCase.endsWith(".D64")
      if (drives(driveID) == c1541 && !isD64) {
        
        showError("Disk attaching error","Format not allowed on a 1541 not in true emulation mode")
        return
      }
      val disk = Diskette(file.toString)
      disk.canWriteOnDisk = canWriteOnDisk
      disk.flushListener = diskFlusher
      drives(driveID).getFloppy.close
      if (!traceDialog.isTracing) clock.pause
      drives(driveID).setDriveReader(disk,emulateInserting)
      clock.play
            
      loadFileItems(driveID).setEnabled(isD64)
      configuration.setProperty(CONFIGURATION_LASTDISKDIR,file.getParentFile.toString)
      val drive = driveID + 8
      fileToLoad match {
        case Some(fn) =>
          val cmd = s"""LOAD"$fn",$drive,1""" + 13.toChar + (if (autorun) "RUN" + 13.toChar else "")
          Keyboard.insertTextIntoKeyboardBuffer(cmd,mmu,true)
        case None if autorun =>
          Keyboard.insertSmallTextIntoKeyboardBuffer(s"""LOAD"*",$drive,1""" + 13.toChar + "RUN" + 13.toChar,mmu,true)
        case _ =>
      }
      driveLeds(driveID).setToolTipText(disk.toString)
    }
    catch {
      case t:Throwable =>
        t.printStackTrace
        
        showError("Disk attaching error",t.toString)
    }
  }
  
 private def zoom(f:Int) : Unit = {
    val dim = new Dimension(vicChip.VISIBLE_SCREEN_WIDTH * f,vicChip.VISIBLE_SCREEN_HEIGHT * f)
    updateScreenDimension(dim)
  }

  private def updateScreenDimension(dim:Dimension): Unit = {
    display.setPreferredSize(dim)
    display.invalidate
    display.repaint()
    displayFrame.pack
  }

  protected def savePrg : Unit = {
    val fc = new JFileChooser
    fc.setCurrentDirectory(new File(configuration.getProperty(CONFIGURATION_LASTDISKDIR,"./")))
    fc.setFileFilter(new FileFilter {
      def accept(f: File) = f.isDirectory || f.getName.toUpperCase.endsWith(".PRG")
      def getDescription = "PRG files"
    })
    fc.showSaveDialog(displayFrame) match {
      case JFileChooser.APPROVE_OPTION =>
        configuration.setProperty(CONFIGURATION_LASTDISKDIR,fc.getSelectedFile.getParentFile.toString)
        val (start,end) = ProgramLoader.savePRG(fc.getSelectedFile,mmu,true)
        Log.info(s"BASIC program saved from $start to $end")
      case _ =>
    }
  }

  private def takeSnapshot  : Unit = {
    val fc = new JFileChooser
    fc.showSaveDialog(displayFrame) match {
      case JFileChooser.APPROVE_OPTION =>
        val file = if (fc.getSelectedFile.getName.toUpperCase.endsWith(".PNG")) fc.getSelectedFile else new File(fc.getSelectedFile.toString + ".png")
      	display.saveSnapshot(file)
      case _ =>
    }
  }
  
  protected def paste : Unit = {
    val clipboard = Toolkit.getDefaultToolkit.getSystemClipboard
    val contents = clipboard.getContents(null)
    if (contents.isDataFlavorSupported(DataFlavor.stringFlavor)) {
      val str = contents.getTransferData(DataFlavor.stringFlavor).toString
      Keyboard.insertTextIntoKeyboardBuffer(str,mmu,true)
    }
  }
  
  protected def setSettingsMenu(optionMenu:JMenu) : Unit = {
    setDriveMenu(optionMenu)

    optionMenu.addSeparator

    val keybMenu = new JMenu("Keyboard")
    optionMenu.add(keybMenu)

    val keybEditorItem = new JMenuItem("Keyboard editor ...")
    keybEditorItem.addActionListener(_ => showKeyboardEditor(true) )
    keybMenu.add(keybEditorItem)
    val loadKeybItem = new JMenuItem("Set keyboard layout ...")
    loadKeybItem.addActionListener(_ => loadKeyboard )
    keybMenu.add(loadKeybItem)
    
    optionMenu.addSeparator

    setVolumeSettings(optionMenu)
    
    optionMenu.addSeparator

    setWarpModeSettings(optionMenu)
    
    optionMenu.addSeparator
    
    val adjustRatioItem = new JMenuItem("Adjust display ratio")
    adjustRatioItem.setAccelerator(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_A,java.awt.event.InputEvent.ALT_DOWN_MASK))
    adjustRatioItem.addActionListener(_ => adjustRatio )
    optionMenu.add(adjustRatioItem)

    val zoomItem = new JMenu("Zoom")
    val groupZ = new ButtonGroup
    optionMenu.add(zoomItem)
    for(z <- 1 to 2) {
      val zoom1Item = new JRadioButtonMenuItem(s"Zoom x $z")
      zoom1Item.addActionListener(_ => zoom(z) )
      val kea = z match {
        case 1 => java.awt.event.KeyEvent.VK_1
        case 2 => java.awt.event.KeyEvent.VK_2
      }
      zoom1Item.setAccelerator(KeyStroke.getKeyStroke(kea,java.awt.event.InputEvent.ALT_DOWN_MASK))
      zoomItem.add(zoom1Item)
      groupZ.add(zoom1Item)
    }

    val vicItem = new JMenu("VIC")
    optionMenu.add(vicItem)
    setRenderingSettings(vicItem)

    setFullScreenSettings(optionMenu)
    // -----------------------------------
    
    optionMenu.addSeparator

    setJoysticsSettings(optionMenu)

    setLightPenSettings(optionMenu)

    setMouseSettings(optionMenu)
    
    optionMenu.addSeparator

    val snapshotItem = new JMenuItem("Take a snapshot...")
    snapshotItem.setAccelerator(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_S,java.awt.event.InputEvent.ALT_DOWN_MASK))
    snapshotItem.addActionListener(_ => takeSnapshot )
    optionMenu.add(snapshotItem)

    val gifRecorderItem = new JMenuItem("GIF recorder...")
    gifRecorderItem.setAccelerator(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_F,java.awt.event.InputEvent.ALT_DOWN_MASK))
    gifRecorderItem.addActionListener(_ => openGIFRecorder )
    optionMenu.add(gifRecorderItem)
    
    optionMenu.addSeparator

    setPauseSettings(optionMenu)
    
    optionMenu.addSeparator

    setPrinterSettings(optionMenu)
    // -----------------------------------

    
    optionMenu.addSeparator

    setSIDSettings(optionMenu)
    // -----------------------------------
    optionMenu.addSeparator

    setDrivesSettings

    val busSnooperActiveItem = new JCheckBoxMenuItem("Bus snoop active")
    busSnooperActiveItem.setSelected(false)
    busSnooperActiveItem.addActionListener(e => busSnooperActive = e.getSource.asInstanceOf[JCheckBoxMenuItem].isSelected )
    optionMenu.add(busSnooperActiveItem)
    // reset setting
    resetSettingsActions = (() => {
      busSnooperActiveItem.setSelected(false)
      busSnooperActive = false
    }) :: resetSettingsActions
    
    optionMenu.addSeparator

    setRemotingSettings(optionMenu)
    
    optionMenu.addSeparator
    
    val IOItem = new JMenu("I/O")
    optionMenu.add(IOItem)
    
    optionMenu.addSeparator

    val rs232Item = new JMenuItem("RS-232 ...")
    rs232Item.addActionListener(_ => manageRS232 )
    IOItem.add(rs232Item)
    
    IOItem.addSeparator

    setFlyerSettings(IOItem)

    setREUSettings(IOItem)

    setGEORamSettings(IOItem)

    // -----------------------------------
    
    IOItem.addSeparator

    setDigiMAXSettings(IOItem)
    
    IOItem.addSeparator
    
    val gmod2Item = new JMenuItem("GMOD2 eeprom file...")
    gmod2Item.addActionListener(_ => chooseGMod2 )
    IOItem.add(gmod2Item)
    
    IOItem.addSeparator

    setCPMSettings(IOItem)

    val romItem = new JMenuItem("ROMs ...")
    optionMenu.add(romItem)
    romItem.addActionListener( _ => ROMPanel.showROMPanel(displayFrame,configuration,true,false,() => saveSettings(false)) )
  }
  
  override protected def setGlobalCommandLineOptions : Unit = {
    super.setGlobalCommandLineOptions
    settings.add("screen-dim",
      "Zoom factor. Valued accepted are 1 and 2",
      (f:Int) => if (f == 1 || f == 2) {
        zoom(f)
        zoomOverride = true
      }
    )
  }
  
  def turnOff  : Unit = {
    if (!headless) saveSettings(configuration.getProperty(CONFIGURATION_AUTOSAVE,"false").toBoolean)
    for(d <- drives)
      d.getFloppy.close
    shutdownComponent
    sys.exit(0)
  }
  
  protected def saveSettings(save:Boolean) : Unit = {
    if (!zoomOverride) {
      val dimension = display.getSize()
      configuration.setProperty(CONFIGURATION_FRAME_DIM, dimension.width + "," + dimension.height)
    }
    configuration.setProperty(CONFIGURATION_FRAME_XY, displayFrame.getX + "," + displayFrame.getY)
    if (save) {
      settings.save(configuration)
      println("Settings saved")
    }
    try {
      val propsFile = new File(new File(scala.util.Properties.userHome),CONFIGURATION_FILENAME)
      val out = new FileWriter(propsFile)
      configuration.store(out, "C64 configuration file")
      out.close
    }
    catch {
      case _:IOException =>
    }
  }

  protected def getRAM = mmu.getRAM
  protected def getCharROM = mmu.CHAR_ROM
  
  // state
  protected def saveState(out:ObjectOutputStream) : Unit = {
    out.writeBoolean(drivesEnabled(0))
    out.writeBoolean(drivesEnabled(1))
    out.writeBoolean(printerEnabled)
  }
  protected def loadState(in:ObjectInputStream) : Unit = {
    drivesEnabled(0) = in.readBoolean
    drivesEnabled(1) = in.readBoolean
    printerEnabled = in.readBoolean
  }
  protected def allowsStateRestoring : Boolean = true
  // -----------------------------------------------------------------------------------------
  
  def turnOn(args:Array[String]) : Unit = {
    swing { setMenu }
    // check help
    if (settings.checkForHelp(args)) {
      println(s"Kernal64, Commodore 64 emulator ver. ${ucesoft.cbm.Version.VERSION} (${ucesoft.cbm.Version.BUILD_DATE})")
      settings.printUsage
      sys.exit(0)
    }
    swing { initComponent }
    // VIC
    swing { displayFrame.pack }
    if (configuration.getProperty(CONFIGURATION_FRAME_DIM) != null) {
      val dim = configuration.getProperty(CONFIGURATION_FRAME_DIM) split "," map { _.toInt }
      swing { updateScreenDimension(new Dimension(dim(0),dim(1))) }
    }
    if (configuration.getProperty(CONFIGURATION_FRAME_XY) != null) {
      val xy = configuration.getProperty(CONFIGURATION_FRAME_XY) split "," map { _.toInt }
      swing { displayFrame.setLocation(xy(0),xy(1)) }
    }
    // SETTINGS
    loadSettings(args)
    // VIEW
    swing { displayFrame.setVisible(!headless) }
    // PLAY
    clock.play
  }
}