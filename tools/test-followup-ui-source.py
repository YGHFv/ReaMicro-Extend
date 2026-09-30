from pathlib import Path
import json
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[1]
UI = ROOT / 'app/src/main/java/com/reamicro/fix/ui'

class FollowupUiSourceTest(unittest.TestCase):
    def test_task_fields_keep_stable_state_objects(self):
        main = (UI / 'ModuleMainActivity.kt').read_text()
        editor = main.split('private fun TaskEditorDialog(', 1)[1].split('private fun EditorField(', 1)[0]
        for field in ('FIELD_DURATION', 'FIELD_BOOKS', 'FIELD_DRAW_LIMIT'):
            self.assertIn(f'state = editor.textFields.getValue({field})', editor)
            self.assertNotIn(f'value = editor.values[{field}]', editor)
        self.assertNotIn('clearFocus(', editor)
        self.assertNotIn('keyboard?.hide()', editor)
        self.assertIn('val textFields = mutableStateMapOf<String, TextFieldState>()', main)

    def test_save_and_time_lock_read_current_buffer_without_async_copy(self):
        main = (UI / 'ModuleMainActivity.kt').read_text()
        self.assertIn('editor.textFields[key]?.text?.toString() ?: value', main)
        self.assertIn('editor.textFields[FIELD_DURATION]?.text?.toString()?.toIntOrNull()', main)
        self.assertNotIn('editor.values[FIELD_DURATION]?.toIntOrNull()', main)
        self.assertIn('TextFieldLineLimits.MultiLine(maxHeightInLines = maxLines)', main)

    def test_icon_strip_excluded_only_in_cross_axis_mode(self):
        main = (UI / 'ModuleMainActivity.kt').read_text()
        pager = main.split('val pagerModifier =', 1)[1].split('HorizontalPager(', 1)[0]
        self.assertIn('if (interceptPager)', pager)
        self.assertIn('Modifier.crossAxisPagerWithExclusion(', pager)
        self.assertIn('Modifier.pagerGestureOverride(', pager)
        self.assertIn('pagerState.currentPage == TAB_CONFIG', pager)
        self.assertIn('?.boundsInWindow()?.contains(position)', pager)
        icon = main.split('private fun LauncherIconPicker()', 1)[1].split('private fun ', 1)[0]
        self.assertIn('.onGloballyPositioned { launcherIconCoordinates = it }', icon)
        self.assertIn('.horizontalScroll(rememberScrollState())', icon)

    def test_filter_retains_library_gesture_and_does_not_consume_child_events(self):
        text = (UI / 'PagerGestureExclusion.kt').read_text()
        self.assertIn('Modifier.horizontalPagerSwipeOverride(pagerState)', text)
        self.assertIn('if (!excluded) libraryPointer.onPointerEvent(pointerEvent, pass, bounds)', text)
        self.assertIn('PointerEventPass.Initial && !inGesture', text)
        self.assertIn('PointerEventPass.Final && pointerEvent.changes.none { it.pressed }', text)
        self.assertNotIn('.consume()', text)
        self.assertNotIn('dispatchRawDelta', text)
        self.assertNotIn('animateScrollBy', text)

    def test_new_source_package_is_real_dex_and_preserves_loader_contract(self):
        with zipfile.ZipFile(ROOT / 'source-files/fanqie.rmsource') as archive:
            manifest = json.loads(archive.read('manifest.json'))
            self.assertEqual('fanqie', manifest['id'])
            self.assertEqual(1, manifest['apiVersion'])
            self.assertEqual('5.5.16-compat4', manifest['version'])
            self.assertEqual('com.reamicro.fix.external.source.FanQieSourceProvider', manifest['entryClass'])
            self.assertEqual(64, len(manifest['referenceSha256']))
            dex = archive.read('classes.dex')
            self.assertTrue(dex.startswith(b'dex\n'))
            self.assertIn(b'FanQieSourceProvider;', dex)
            self.assertIn(b'com.reamicro.fix.association.network.AssociationNetworkScope', dex)
        self.assertTrue((ROOT / 'source-files/fanqie/src/com/reamicro/fix/external/source/FanQieSourceProvider.java').is_file())
        self.assertTrue((ROOT / 'tools/build-fanqie-source.py').is_file())

if __name__ == '__main__':
    unittest.main()