from pathlib import Path

root = Path('.')
project = root / 'RealCoords'

build = project / 'build.gradle.kts'
content = build.read_text(encoding='utf-8')
start = content.index('repositories {')
end = content.index('\n}\n\ndependencies', start)
content = content[:start] + '''repositories {
    mavenCentral()
    maven('https://repo.opencollab.dev/main/')
}''' + content[end + 2:]
build.write_text(content, encoding='utf-8')

props = project / 'gradle.properties'
props.write_text(
    '# Build target: Geyser 2.12.1 / Java 21\n'
    'geyserVersion=2.12.1-SNAPSHOT\n'
    'javaVersion=21\n',
    encoding='utf-8',
)

extension = project / 'src/main/resources/extension.yml'
ext_text = extension.read_text(encoding='utf-8')
ext_text = ext_text.replace('api: 1.0.0', 'api: 2.12.1')
extension.write_text(ext_text, encoding='utf-8')

source = project / 'src/main/java/dev/realcoords/ShiftSession.java'
source_text = source.read_text(encoding='utf-8')
old_import = 'import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventoryTransactionType;'
new_import = old_import + chr(10) + 'import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.ItemUseTransaction;'
if 'import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.ItemUseTransaction;' not in source_text:
    if old_import not in source_text:
        raise SystemExit('Could not find InventoryTransactionType import')
    source_text = source_text.replace(old_import, new_import)

source_text = source_text.replace('EntityDataTypes.PLAYER_BED_POSITION', 'EntityDataTypes.BED_POSITION')
source_text = source_text.replace('EntityDataTypes.BLOCK_TARGET', 'EntityDataTypes.BLOCK_TARGET_POS')

method_marker = '    private void onAuthInput(PlayerAuthInputPacket a) {'
if 'private void invTx(ItemUseTransaction t)' not in source_text:
    method = '''
    private void invTx(ItemUseTransaction t) {
        if (t == null) return;
        if (t.getPlayerPosition() != null) t.setPlayerPosition(toR(t.getPlayerPosition()));
        // Action type 2 is the block item-interaction transaction.
        if (t.getActionType() == 2 && t.getBlockPosition() != null) {
            t.setBlockPosition(toR(t.getBlockPosition()));
        }
    }

'''
    if method_marker not in source_text:
        raise SystemExit('Could not find onAuthInput insertion point')
    source_text = source_text.replace(method_marker, method + method_marker, 1)

old_action = 'it.set(new PlayerBlockActionData(x.getAction(), toR(x.getBlockPosition()), x.getFace()));'
if old_action in source_text:
    source_text = source_text.replace(old_action, 'x.setBlockPosition(toR(x.getBlockPosition()));')
if 'new PlayerBlockActionData(' in source_text:
    raise SystemExit('Unsupported PlayerBlockActionData constructor remains')
if 'EntityDataTypes.PLAYER_BED_POSITION' in source_text or 'EntityDataTypes.BLOCK_TARGET)' in source_text:
    raise SystemExit('Outdated EntityDataTypes reference remains')
source.write_text(source_text, encoding='utf-8')

print('Migrated source and applied Geyser 2.12.1 compatibility edits.')
