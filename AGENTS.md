# Working in MJ Watch

- Keep RAM and token use low. Read targeted files and limit tool output. Run memory-heavy builds and emulators one at a time unless a wider run is needed to verify the change.
- Delegate only independent work that benefits from parallel execution. Give each subagent a narrow brief and only the context it needs. Start with a fast, lower-cost model for focused work and use a stronger model when the complexity warrants it.
- Before finishing, remove temporary files and stop processes created for the task when they are no longer needed. When cleaning older development files, verify that they are reproducible or disposable and preserve source changes, releases, credentials, device data, and evidence still in use.
