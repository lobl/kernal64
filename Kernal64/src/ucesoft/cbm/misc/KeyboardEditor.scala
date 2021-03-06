package ucesoft.cbm.misc

import ucesoft.cbm.peripheral.keyboard.{CKey, Keyboard, KeyboardMapper, KeyboardMapperStore}
import javax.swing._
import java.awt.FlowLayout
import java.awt.GridLayout
import java.awt.event.ActionListener
import java.awt.event.ActionEvent
import java.awt.event.KeyListener
import java.awt.event.KeyEvent
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Font

class KeyboardEditor(keyboard:Keyboard,keybm:KeyboardMapper,isC64:Boolean) extends JPanel with ActionListener with KeyListener {
  private val map = {
    val m = new collection.mutable.HashMap[CKey.Key,Int]
    for(kv <- keybm.map) m += ((kv._2,kv._1))
    m
  }
  private val keypad_map = {
    val m = new collection.mutable.HashMap[CKey.Key,Int]
    for(kv <- keybm.keypad_map) m += ((kv._2,kv._1))
    m
  }
  
  private case class ButtonKey(key:CKey.Key,keyCode:Option[Int]) {
    override def toString = keyCode match {
      case Some(kc) => KeyboardMapperStore.getKey(kc)
      case None => "EMPTY"
    }
  }
  
  private val keys = (CKey.values filter { k => if (isC64) !CKey.is128Key(k) else true } filterNot { k => k == CKey.L_SHIFT || k == CKey.R_SHIFT } toArray) sortBy { k => k.toString }
  private val maxKeyLen = keys map { _.toString.length } max
  private val keyButtons : Array[ButtonKey] = keys map { k =>
    findKeyCode(k) match {
      case Some(vk) =>
        ButtonKey(k,Some(vk))
      case None =>
        ButtonKey(k,None)
    }
  }
  private def getButtonColor(bk:ButtonKey) : Color = {
    bk.keyCode match {
      case None =>
        Color.RED
      case Some(c) =>
        if (KeyboardMapperStore.isExtendedKey(c)) Color.BLUE else Color.BLACK
    }
  }
  private val buttons = keyButtons map { k => 
    val b = new JButton(k.toString)
    b.setForeground(getButtonColor(k))
    b.setActionCommand(k.key.toString)
    b.addActionListener(this)
    b
  }
  private val tiles = for(k <- keys.zip(buttons)) yield new JPanel {
    setLayout(new FlowLayout(FlowLayout.LEFT))
    val lab = k._1.toString + (" " * (maxKeyLen - k._1.toString.length))
    val jlabel = new JLabel(lab)
    add(jlabel)
    val f = jlabel.getFont
    jlabel.setFont(new Font("Monospaced",f.getStyle,f.getSize))
    add(k._2)
  }
  private val statusLabel = new JLabel("Press a button to redefine a key...")
  private var waitingIndex = -1
  private val gridPanel = new JPanel(new GridLayout(0,5))
  private val statusPanel = new JPanel(new FlowLayout(FlowLayout.LEFT))
  private val saveButton = new JButton("Save as ...")
  
  setLayout(new BorderLayout)
  add("Center",new JScrollPane(gridPanel))
  add("South",statusPanel)
  for(t <- tiles) gridPanel.add(t)
  
  statusLabel.setForeground(Color.BLACK)
  statusPanel.add(saveButton)
  statusPanel.add(statusLabel)
  addKeyListener(this)
  setFocusTraversalKeysEnabled(false)
  saveButton.addActionListener(this)
  saveButton.setActionCommand("SAVE")
  
  private def findKeyCode(k:CKey.Key) : Option[Int] = {
    map get k match {
      case s@Some(_) => s
      case None =>
        keypad_map get k
    }
  }
  
  def actionPerformed(e:ActionEvent) : Unit = {
    if (e.getActionCommand == "SAVE") {
      save
      return
    }
    val key = CKey.withName(e.getActionCommand)
    waitingIndex = keys.indexOf(key)
    for(b <- buttons) b.setEnabled(false)
    statusLabel.setText(s"Press a key to redefine C= key $key")
    statusLabel.setForeground(Color.RED)
    requestFocus
  }
  
  def keyPressed(e:KeyEvent) : Unit = {
    val buttonKey = if (e.getKeyCode != KeyEvent.VK_UNDEFINED) ButtonKey(keys(waitingIndex),Some(e.getKeyCode))
                    else ButtonKey(keys(waitingIndex),Some(e.getExtendedKeyCode))
    keyButtons(waitingIndex) = buttonKey
    buttons(waitingIndex).setText(buttonKey.toString)
    buttons(waitingIndex).setForeground(getButtonColor(buttonKey))

    val alreadyExists = keypad_map.filter( kv => kv._1 != keys(waitingIndex) && kv._2 == e.getKeyCode ) ++ map.filter( kv => kv._1 != keys(waitingIndex) && kv._2 == e.getKeyCode )
    if (!alreadyExists.isEmpty) {
      val keys = alreadyExists.keys.mkString(",")
      JOptionPane.showMessageDialog(this,s"Other keys have the same binding: $keys","Key binding warning",JOptionPane.WARNING_MESSAGE,null)
    }

    if (e.getKeyLocation == KeyEvent.KEY_LOCATION_NUMPAD) {
      if (isC64) JOptionPane.showMessageDialog(this,"Keypad must be used in C128 mode only","Error",JOptionPane.ERROR_MESSAGE,null)
      else keypad_map(keys(waitingIndex)) = e.getKeyCode
    }
    else {
      if (e.getKeyCode != KeyEvent.VK_UNDEFINED) map(keys(waitingIndex)) = e.getKeyCode
      else map(keys(waitingIndex)) = e.getExtendedKeyCode

      keyboard.setKeyboardMapper(makeKeyboardMapper)
    }
    
    for(b <- buttons) b.setEnabled(true)
    statusLabel.setText("Press a button to redefine a key...")
    statusLabel.setForeground(Color.BLACK)
  }
  def keyReleased(e:KeyEvent) : Unit = {}
  def keyTyped(e:KeyEvent) : Unit = {}
  
  private def makeKeyboardMapper : KeyboardMapper = new KeyboardMapper {
    val map = KeyboardEditor.this.map map { kv => (kv._2,kv._1) } toMap
    val keypad_map = KeyboardEditor.this.keypad_map map { kv => (kv._2,kv._1) } toMap
  }
  
  private def save  : Unit = {
    val fc = new JFileChooser
    fc.setDialogTitle("Choose where to save this keyboard configuration")
    val fn = fc.showSaveDialog(this) match {
      case JFileChooser.APPROVE_OPTION =>
        val kbm = makeKeyboardMapper
        import java.io._
        val pw = new PrintWriter(new FileOutputStream(fc.getSelectedFile))
        KeyboardMapperStore.store(kbm,pw)
        pw.close
      case _ =>
    }
  }
}