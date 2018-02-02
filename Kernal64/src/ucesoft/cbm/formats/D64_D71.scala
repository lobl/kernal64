package ucesoft.cbm.formats
import java.io.RandomAccessFile
import java.io.ObjectOutputStream
import java.io.ObjectInputStream
import scala.collection.mutable.ListBuffer
import java.io.IOException
import java.io.FileNotFoundException
import java.util.StringTokenizer
import ucesoft.cbm.cpu.Memory
import ucesoft.cbm.peripheral.drive.Floppy

private[formats] class D64_D71(val file: String,empty:Boolean = false) extends Diskette {
  import Diskette._
  val canBeEmulated = true
  // D64
  private[this] final  val DISK_SIZE_40_TRACKS = 196608
  private[this] final  val DISK_SIZE_35_TRACKS = 174848
  private[this] final  val DISK_SIZE_35_TRACKS_WITH_ERRORS = 175531
  private[this] final  val DISK_SIZE_40_TRACKS_WITH_ERRORS = 197376
  // D71
  private[this] final  val D71_DISK_SIZE_70_TRACKS = 349696
  private[this] final  val D71_DISK_SIZE_70_TRACKS_WITH_ERRORS = 351062
  
  private[this] final  val BYTES_PER_SECTOR = 256
  private[this] final  val DIR_TRACK = 18
  private[this] final  val DIR_SECTOR = 1
  private[this] final  val BAM_SECTOR = 0
  /*
   *     Track       Sec/trk   # Sectors
        --------------  -------   ---------
         1-17 (side 0)    21         357
        18-24 (side 0)    19         133
        25-30 (side 0)    18         108
        31-35 (side 0)    17          85
        36-52 (side 1)    21         357
        53-59 (side 1)    19         133
        60-65 (side 1)    18         108
        66-70 (side 1)    17          85
                                     ---
                              total 1366
   */
  private[this] final val TRACK_ALLOCATION = // Key = #track => Value = #sectors per track
    (for (t <- 0 to 70) yield {
      if (t <= 17) (t, 21)
      else if (t <= 24) (t, 19)
      else if (t <= 30) (t, 18)
      else if (t <= 35) (t, 17)
      else if (t <= 52) (t, 21)
      else if (t <= 59) (t, 19)
      else if (t <= 65) (t, 18)
      else (t,17)
    }).toMap
  
  private[this] val d64 = new RandomAccessFile(file, "rw")
  private[this] val absoluteSectorCache = new collection.mutable.HashMap[Int,Int]
  private[this] var _bam : BamInfo = if (!empty) bamInfo else null
  
  TOTAL_TRACKS // just to check for a valid track format
  
  private[this] val GCRImage = {
    val gcr = Array.ofDim[Array[Array[Int]]](TOTAL_TRACKS)
    for(i <- 0 until gcr.length) {
      gcr(i) = Array.ofDim[Array[Int]](TRACK_ALLOCATION(i + 1))
    }
    gcr
  }  
  
  @inline private def absoluteSector(t: Int, s: Int) = {
    val cacheIndex = t << 8 | s
    absoluteSectorCache get cacheIndex match {
      case None =>
        val pos = (1 until t).foldLeft(0) { (acc, t) => acc + TRACK_ALLOCATION(t) } + s
        absoluteSectorCache += cacheIndex -> pos
        pos
      case Some(pos) =>
        pos
    }    
  }
  
  private def TOTAL_TRACKS = d64.length match {
    case DISK_SIZE_35_TRACKS|DISK_SIZE_35_TRACKS_WITH_ERRORS => 35
    case DISK_SIZE_40_TRACKS|DISK_SIZE_40_TRACKS_WITH_ERRORS => 40
    case D71_DISK_SIZE_70_TRACKS | D71_DISK_SIZE_70_TRACKS_WITH_ERRORS => if (_bam != null && _bam.singleSide) 35 else 70
    case _ => if (empty) 70 else throw new IllegalArgumentException("Unsupported file format. size is " + d64.length)
  }
  def TOTAL_AVAILABLE_SECTORS = d64.length match {
    case DISK_SIZE_35_TRACKS|DISK_SIZE_35_TRACKS_WITH_ERRORS => 683 - 19 // 18 for directory 1 for bam
    case DISK_SIZE_40_TRACKS|DISK_SIZE_40_TRACKS_WITH_ERRORS => 768 - 19
    case D71_DISK_SIZE_70_TRACKS | D71_DISK_SIZE_70_TRACKS_WITH_ERRORS => 1366 - 19 * 2
  }
  
  def bam = _bam
  
  // CONSTRUCTOR
  //println(directories.mkString("\n"))
  if (!empty) loadGCRImage
  
  private def gcrImageOf(t:Int,s:Int) = if (t == 0 || t > GCRImage.length) GCR.EMPTY_GCR_SECTOR else GCRImage(t - 1)(s)
  
  @inline private def getSectorError(t:Int,s:Int) : Option[Int] = {
    d64.length match {
      case DISK_SIZE_35_TRACKS|DISK_SIZE_40_TRACKS => None
      case DISK_SIZE_35_TRACKS_WITH_ERRORS =>
        d64.seek(DISK_SIZE_35_TRACKS + absoluteSector(t,s))
        Some(d64.read)
      case DISK_SIZE_40_TRACKS_WITH_ERRORS =>
        d64.seek(DISK_SIZE_40_TRACKS + absoluteSector(t,s))
        Some(d64.read)
      case D71_DISK_SIZE_70_TRACKS => None
      case D71_DISK_SIZE_70_TRACKS_WITH_ERRORS => 
        d64.seek(D71_DISK_SIZE_70_TRACKS + absoluteSector(t,s))
        Some(d64.read)
    }
  }
  
  private def loadGCRImage {
    for(t <- 1 to TOTAL_TRACKS;
    	s <- 0 until TRACK_ALLOCATION(t)) GCRImage(t - 1)(s) = GCR.sector2GCR(s,t,readBlock(t,s),bam.diskID,getSectorError(t,s))    
  }
  
  private def writeGCRSector(t:Int,s:Int,gcrSector:Array[Int]) {
    GCRImage(t - 1)(s) = gcrSector
    val sector = GCR.GCR2sector(gcrSector)
    // write on disk
    d64.seek(absoluteSector(t,s) * BYTES_PER_SECTOR)
    for(i <- 0 until sector.length) {
      d64.write(sector(i))
    }
  }
  
  def close = {
    d64.close
  }
  
  def format(formatCmd:String) {
    // N[0]:<diskname>,id
    val cmd = if (formatCmd.charAt(formatCmd.length - 1) == 13) formatCmd.dropRight(1) else formatCmd
    cmd split ":" match {
      case Array(_,rest) =>
        rest split "," match {
          case Array(diskName,id) =>
            if (diskName.length == 0 || diskName.length > 16 || id.length != 2) throw new IllegalArgumentException("Bad diskName='" + diskName + "' or bad id='" + id + "'")            
            val emptyDisk = getClass.getResourceAsStream(if (file.toUpperCase.endsWith(".D71")) "/resources/emptyDisk.d71" else "/resources/emptyDisk.d64")
            if (emptyDisk == null) throw new IllegalArgumentException("Cannot find disk template")
            d64.seek(0)
            var b = emptyDisk.read
            while (b != -1) {
              d64.writeByte(b)
              b = emptyDisk.read
            }
            emptyDisk.close
            d64.seek(absoluteSector(DIR_TRACK, BAM_SECTOR) * BYTES_PER_SECTOR + 0x90)
            for(i <- 0 until diskName.length) d64.writeByte(diskName.charAt(i))
            for(_ <- 1 to (16 - diskName.length)) d64.writeByte(0xA0)
            d64.skipBytes(2)
            d64.writeByte(id.charAt(0))
            d64.writeByte(id.charAt(1))
            _bam = bamInfo
            loadGCRImage
          case _ => throw new IllegalArgumentException
        }
      case _ => throw new IllegalArgumentException
    }
  }
  
  def readBlock(track:Int,sector:Int) = {
    d64.seek(absoluteSector(track,sector) * BYTES_PER_SECTOR)
    val buffer = Array.ofDim[Byte](BYTES_PER_SECTOR)
    d64.read(buffer)
    buffer
  }

  private def bamInfo = {    
    d64.seek(absoluteSector(DIR_TRACK, BAM_SECTOR) * BYTES_PER_SECTOR + 3)
    val singleSide = file.toUpperCase.endsWith(".D64") || d64.read != 0x80
    var freeSectors = 0
    d64.seek(absoluteSector(DIR_TRACK, BAM_SECTOR) * BYTES_PER_SECTOR + 4)
    for(_ <- 1 to 35) {
      freeSectors += d64.readByte
      d64.skipBytes(3)
    }
    d64.seek(absoluteSector(DIR_TRACK, BAM_SECTOR) * BYTES_PER_SECTOR + 0x90)
    val diskName = new StringBuilder
    var i = 0
    while (i < 16) {
      val c = d64.readByte.toInt & 0xFF
      if (c != 0xA0) diskName.append(c.toChar)
      i += 1
    }
    d64.skipBytes(2)
    val diskID = "" + d64.readByte.toChar + d64.readByte.toChar
    d64.skipBytes(1)
    val dosType = "" + d64.readByte.toChar + d64.readByte.toChar
    BamInfo(diskName.toString, diskID, dosType,singleSide,freeSectors)
  }

  def loadInMemory(mem: Memory, fileName: String, relocate: Boolean,c64Mode:Boolean=true) = {
    load(fileName) match {
      case FileData(fn, startAddress, data) =>
        val initialAddress = relocate match {
          case true => startAddress
          case false => ProgramLoader.startBASICAddress(mem,c64Mode)
        }
        for (m <- initialAddress until initialAddress + data.length) mem.write(m, data(m - initialAddress))
        val endAddress = initialAddress + data.length
        println("Loaded " + fn + " from " + initialAddress + " to " + endAddress)
        ProgramLoader.updateBASICPointers(mem,initialAddress, endAddress,c64Mode)
        endAddress
    }
  }
  
  private def loadPRG(entry: DirEntry) = {
    val buffer = Array.ofDim[Byte](BYTES_PER_SECTOR)
    val data = new ListBuffer[Int]
    var lastChunk = false
    var isFirstChunk = true
    var startAddress = 0
    var t = entry.t
    var s = entry.s
    while (!lastChunk) {
      d64.seek(absoluteSector(t, s) * BYTES_PER_SECTOR)
      d64.read(buffer)
      var chunkIndex = 0
      t = buffer(0)
      s = buffer(1).toInt & 0xFF
      lastChunk = t == 0
      if (isFirstChunk) {
        isFirstChunk = false
        startAddress = (buffer(2).toInt & 0xFF) + (buffer(3).toInt & 0xFF) * 256
        chunkIndex += 4
      } else chunkIndex += 2
      val lastByte = if (lastChunk) s else 255
      while (chunkIndex <= lastByte) {
        data += buffer(chunkIndex).toInt & 0xFF
        chunkIndex += 1
      }
    }
    FileData(entry.fileName, startAddress, data.toArray)
  }
  
  private def loadSEQ(entry: DirEntry) = {
    val buffer = Array.ofDim[Byte](BYTES_PER_SECTOR)
    val data = new ListBuffer[Int]
    var lastChunk = false
    var t = entry.t
    var s = entry.s
    while (!lastChunk) {
      d64.seek(absoluteSector(t, s) * BYTES_PER_SECTOR)
      d64.read(buffer)
      var chunkIndex = 0
      t = buffer(0)
      s = buffer(1).toInt & 0xFF
      lastChunk = t == 0
      chunkIndex += 2
      val lastByte = if (lastChunk) s else 255
      while (chunkIndex <= lastByte) {
        data += buffer(chunkIndex).toInt & 0xFF
        chunkIndex += 1
      }
    }
    FileData(entry.fileName, -1, data.toArray)
  }
  
  def load(fileName: String,fileType:FileType.Value = FileType.PRG) = {
    if (fileName.startsWith("$")) formatDirectoriesAsPRG(fileName)
    else {
      val dpos = fileName.indexOf(":")
      val st = new StringTokenizer(if (dpos != -1) fileName.substring(dpos + 1) else fileName,",")
      val fn = st.nextToken
      val ft = if (st.hasMoreTokens && st.nextToken == "S") FileType.SEQ else fileType
      
      directories find { e =>
        ft == e.fileType && fileNameMatch(fn,e.fileName)
      } match {
        case None => throw new FileNotFoundException(fileName)
        case Some(entry) =>
          entry.fileType match {
            case FileType.PRG => loadPRG(entry)
            case FileType.SEQ => loadSEQ(entry)
            case _ => throw new IOException("Bad file type: " + entry.fileType)
          }
      }
    }
  }
  
  private def formatDirectoriesAsPRG(fileName:String) = {
    val colonPos = fileName.indexOf(":")
    val dirs = if (colonPos == -1) directories else {
      val filter = fileName.substring(colonPos + 1)
      val asteriskPos = filter.indexOf('*')
      directories filter { fn =>
        if (asteriskPos == -1) fn.fileName == filter else fn.fileName.startsWith(filter.substring(0,asteriskPos))
      }      
    }
    val out = new ListBuffer[Int]
    val bam = bamInfo
    
    // set start address to $0801
    var ptr = 0x801
    // write next line address
    ptr += 30
    out.append(ptr & 0xFF) 	// L
    out.append(ptr >> 8)	// H
    // write label
    out.append(0) // drive L
    out.append(0) // drive H
    out.append(0x12) // RVS ON
    out.append(0x22) // "
    for(i <- 0 until 16) {
      if (i < bam.diskName.length) out.append(bam.diskName.charAt(i)) else out.append(0x20)
    }
    out.append(0x22) // "
    out.append(0x20)
    out.append(bam.diskID(0))
    out.append(bam.diskID(1))
    out.append(0x20)
    out.append(bam.dosType(0))
    out.append(bam.dosType(1))
    out.append(0x00)	// EOL
    for(dir <- dirs) {
      val blanks = if (dir.sizeInSectors < 10) 3 
      	else
        if (dir.sizeInSectors < 100) 2
        else 1
      // write next line address
      ptr += blanks + 2 + 2 + 18 + 5
      val endBlanks = 32 - (blanks + 2 + 2 + 18 + 5)
      out.append(ptr & 0xFF) 	// L
      out.append(ptr >> 8)	// H
      // write blocks
      out.append(dir.sizeInSectors & 0xFF)
      out.append(dir.sizeInSectors >> 8)
      // blanks after blocks      
      for(i <- 1 to blanks) out.append(0x20)
      out.append(0x22) // "
      for(i <- 0 until dir.fileName.length) out.append(dir.fileName.charAt(i))
      out.append(0x22) // "
      for(i <- 1 to 16 - dir.fileName.length) out.append(0x20)
      out.append(0x20) // "
      val fileType = dir.fileType.toString
      for(i <- 0 until fileType.length) out.append(fileType.charAt(i))
      for(i <- 1 to endBlanks) out.append(0x20)
      out.append(0x00) // EOL
    }
    
    val blocksFreeText = "BLOCKS FREE."
    // write next line address
    ptr += 2 + 2 + blocksFreeText.length + 1
    out.append(ptr & 0xFF) 	// L
    out.append(ptr >> 8)	// H
    val blocksFree = bamInfo.freeSectors//TOTAL_AVAILABLE_SECTORS - (dirs map { _.sizeInSectors } sum)
    // write block free
    out.append(blocksFree & 0xFF) 	// L
    out.append(blocksFree >> 8)		// H    
    for(i <- 0 until blocksFreeText.length) out.append(blocksFreeText.charAt(i))
    out.append(0x00) // EOL
    
    out.append(0x00)
    out.append(0x00)
    FileData("$",0x801,out.toArray)
  }

  def directories = {
    var t = DIR_TRACK
    var s = DIR_SECTOR
    var dirs = new ListBuffer[DirEntry]
    var readNextSector = true
    val buffer = Array.ofDim[Byte](0x20)
    while (readNextSector) {      
      d64.seek(absoluteSector(t, s) * BYTES_PER_SECTOR)
      var firstEntryOfSector = true
      var entryIndex = 0
      var readNextEntry = true
      while (readNextEntry) {
        d64.read(buffer)
        if (firstEntryOfSector) {
          firstEntryOfSector = false
          val nextT = buffer(0)
          val nextS = buffer(1)          
          if (nextT != 0) {
            t = nextT
            s = nextS
          } 
          else readNextSector = false
        }                
        entryIndex += 1
        if (entryIndex == 9 || buffer.forall(_ == 0)) {
          readNextEntry = false // last+1 entry of this sector
        }
        else {                              
          val fileType = FileType(buffer(2) & 3)
          val track = buffer(3)
          val sector = buffer(4)
          val fileName = new StringBuilder
          var a0Found = false
          var i = 5
          val a0 = 0xA0.toByte
          while (i < 0x15 && !a0Found) {
            if (buffer(i) == a0) a0Found = true
            else fileName.append((buffer(i) & 0xFF).toChar)            
            i += 1
          }
          val size = buffer(0x1E).toInt & 0xFF + (buffer(0x1F).toInt & 0xFF) * 256
          val entry = DirEntry(fileType, fileName.toString, track, sector, size)
          dirs += entry
        }
      }
    }
    dirs.toList
  }
  
  // --------------------- Floppy -------------------------
  val isReadOnly = false
  val isFormattable = false
  val totalTracks = TOTAL_TRACKS
  override lazy val singleSide = bamInfo.singleSide
  
  private[this] var track = 1
  private[this] var sector = 0
  private[this] var gcrSector = gcrImageOf(track, sector)
  private[this] var sectorsPerCurrentTrack = TRACK_ALLOCATION(track)
  private[this] var gcrIndex = 0
  private[this] var sectorModified = false
  private[this] var trackChangeListener : Floppy#TrackListener = null
  private[this] var bit = 1
  private[this] var _side = 0
  private[this] var trackSideBase = 0
  
  override def minTrack = _side match {
    case 0 =>
      1
    case 1 =>
      (totalTracks >> 1) + 1
  }
  override def maxTrack = _side match {
    case 0 =>
      if (bam.singleSide) totalTracks else totalTracks >> 1
    case 1 =>
      totalTracks
  }
  
  override def side = _side
  override def side_=(newSide:Int) {
    val oldT = track
    newSide match {
      case 0 if _side == 1 =>
          track -= (if (bam.singleSide) TOTAL_TRACKS else TOTAL_TRACKS >> 1)
          sector = 0
          gcrIndex = 0
          sectorsPerCurrentTrack = TRACK_ALLOCATION(track)
          gcrSector = gcrImageOf(track, sector)
      case 1 if _side == 0 =>        
          track += (if (bam.singleSide) TOTAL_TRACKS else TOTAL_TRACKS >> 1)
          sector = 0
          gcrIndex = 0
          sectorsPerCurrentTrack = TRACK_ALLOCATION(track)
          gcrSector = gcrImageOf(track, sector)
      case _ =>
    }
    //println(s"Side changed: ${_side} -> $newSide $oldT => $track")
    _side = newSide    
  }
  
  def reset {
    _side = 0
    track = 1
    sector = 0
    gcrSector = gcrImageOf(track, sector)
    sectorsPerCurrentTrack = TRACK_ALLOCATION(track)
    gcrIndex = 0
    sectorModified = false
    bit = 1
  }
  
  def nextBit = {
    val b = (gcrSector(gcrIndex) >> (8 - bit)) & 1
    if (bit == 8) rotate else bit += 1
    b
  }
  def writeNextBit(value:Boolean) {
    sectorModified = true
    val mask = 1 << (8 - bit)
    if (value) gcrSector(gcrIndex) |= mask else gcrSector(gcrIndex) &= ~mask
    if (bit == 8) rotate else bit += 1
  }
  
  @inline private def rotate {
    bit = 1
    gcrIndex += 1
    if (gcrIndex >= gcrSector.length) { // end of current sector
      if (sectorModified) {
        sectorModified = false
        writeGCRSector(track,sector,gcrSector)
      }
      gcrIndex = 0
      sector = (sector + 1) % sectorsPerCurrentTrack        
      gcrSector = gcrImageOf(track, sector)  
    }
  }
  
  def notifyTrackSectorChangeListener = if (trackChangeListener != null) trackChangeListener(track,false,Some(sector))
  def currentTrack = track
  def currentSector = Some(sector)
  /**
   * tracksteps & 1 == 0 are valid tracks, the others are half tracks not used
   * in the D64 format.
   */
  def changeTrack(trackSteps:Int) {
    val isOnTrack = (trackSteps & 1) == 0    
    if (isOnTrack) {
      val newTrack = trackSteps >> 1
      if (track != newTrack) {
        if (sectorModified) {
          sectorModified = false
          writeGCRSector(track,sector,gcrSector)
        } 
        track = trackSteps >> 1
        sectorsPerCurrentTrack = TRACK_ALLOCATION(track)
        sector = 0   
        bit = 1
        gcrSector = gcrImageOf(track, sector)
        gcrIndex = gcrIndex % gcrSector.length
        notifyTrackSectorChangeListener
      }
    }
  }
  
  def setTrackChangeListener(l:Floppy#TrackListener) = trackChangeListener = l
  
  override def toString = s"D64 fileName=$file totalTracks=$TOTAL_TRACKS t=$track s=$sector"
  // state
  def save(out:ObjectOutputStream) {
    out.writeInt(_side)
    out.writeInt(track)
    out.writeInt(sector)
    out.writeInt(gcrIndex)
    out.writeInt(bit)
    out.writeBoolean(sectorModified)
  }
  def load(in:ObjectInputStream) {
    _side = in.readInt
    track = in.readInt
    sector = in.readInt
    gcrIndex = in.readInt
    bit = in.readInt
    sectorModified = in.readBoolean
    sectorsPerCurrentTrack = TRACK_ALLOCATION(track)
    gcrSector = gcrImageOf(track, sector)
  }
}