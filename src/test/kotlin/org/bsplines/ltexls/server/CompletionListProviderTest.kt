/* Copyright (C) 2019-2023 Julian Valentin, LTeX Development Community
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.bsplines.ltexls.server

import org.eclipse.lsp4j.CompletionList
import org.eclipse.lsp4j.Position
import kotlin.test.Test
import kotlin.test.assertEquals

class CompletionListProviderTest {
  @Test
  fun testCreateCompletionList() {
    val languageServer = LtexLanguageServer()
    languageServer.settingsManager.settings = languageServer.settingsManager.settings.copy(
      _allDictionaries = mapOf(Pair("en-US", setOf("testfoobar"))),
    )

    val document =
      LtexTextDocumentItem(languageServer, "untitled:test.md", "markdown", 1, "This is a test.\n")
    val completionList: CompletionList =
      languageServer.completionListProvider.createCompletionList(document, Position(0, 14))

    assertEquals(completionList.items.size, 0)
  }
}
