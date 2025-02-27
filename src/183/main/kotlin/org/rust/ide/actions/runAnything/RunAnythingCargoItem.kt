/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.ide.actions.runAnything

import java.awt.Component
import javax.swing.Icon

class RunAnythingCargoItem(command: String, icon: Icon) : RunAnythingCargoItemBase(command, icon) {
    override fun createComponent(isSelected: Boolean): Component {
        return super.createComponent(isSelected).also(this@RunAnythingCargoItem::customizeComponent)
    }
}
