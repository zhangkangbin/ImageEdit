# R8 uses proguard-android-optimize.txt plus dependency consumer rules.
# Lifecycle already keeps the AndroidViewModel(Application) constructor.
# Drafts and presets use explicit JSON fields, without reflective serialization.
# Add targeted keep rules here only when introducing dynamic class references.
