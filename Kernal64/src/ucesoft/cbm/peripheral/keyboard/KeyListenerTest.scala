package ucesoft.cbm.peripheral.keyboard

import javax.swing.JFrame

import java.awt.event.KeyEvent
import javax.swing.JPanel
import ucesoft.cbm.Log
import java.awt.event.KeyListener

object QKeyListenerTest extends App with KeyListener {
    Log.setDebug
//	val kb = new Keyboard
//	val cp = ControlPort.keypadControlPort
	
	val f = new JFrame("Key Test")
	f.setSize(100,100)
	val keyPanel = new JPanel
	f.getContentPane.add("Center",keyPanel)
//	f.addKeyListener(kb)
//	f.addKeyListener(cp)
	f.addKeyListener(this)
	f.requestFocus()
	f.setVisible(true)

  var lastCode = 0
	
	def keyPressed(e:KeyEvent) {
    printEvent("Pressed",e)
  }
    
  def keyReleased(e:KeyEvent) {
    lastCode = 0
    printEvent("Released",e)
    lastCode = 0
  }

  private def printEvent(s:String,e:KeyEvent): Unit = {
    val code = if (e.getKeyCode != 0) e.getKeyCode else e.getExtendedKeyCode
    if (code != lastCode) {
      lastCode = code
      println(s + " " + code + " " + KeyEvent.getKeyText(code))
    }
  }
  
  def keyTyped(e:KeyEvent) {}
}