The message must use chinese.

Your commit message should follow the following style, example:

```
add(api): a description here
 - a short bullet point here
 - another short bullet point here
 - more details here
```

The commit description (which comes right after the "type(scope):" must not be sentence-case, start-case, pascal-case, upper-case [subject-case] and not end with a period (.) and must not be over 74 characters in length.

"add" is the type, I'll list all possible types.
"api" is the scope, I'll list all possible scopes.

Possible scopes:
- api (code related to app api) - doc (code related to ascii docs) - config (code related to configuration) - task (code related to task) - ng-admin (code related to app fe-admin) - workspace (in case any workspace code was modified and you can't infer the app, you can use workspace scope) -tour(code related to tour file)

Possible types:
- add, use this if you think the code adds something or creates a new feature - fix, use this if you think the code fixed something - perf, use this if you think the code makes performance improvements - docs, use this if you think the code does anything related to documentation - refactor, use this if you think that the change is simple a refactor but the functionality is the same - test, use this if this change is related to testing code (.spec, .test, etc) - chore, use this for code related to maintenance tasks, build processes, or other non-user-facing changes.
  It typically includes tasks that don't directly impact the functionality but are necessary for the project's development and maintenance.
- ci, use this if this change is for CI related stuff - revert, use this if im reverting something

After the commit message, please add a blank line and then summarise the details of the commit in a series of short bullet points (no more than 70 characters each).
Please, where possible, group changes with a common theme into a single line concisely describing the purpose of the change.

I'll send you an output of 'git diff --staged' command.
Lines must not be longer than 74 characters.
Do not wrap your response in any markdown syntax.
Don't invent meaning where you are uncertain.
If the response is out of the token limit, use the same style and chop down the commit message.

**Remember: the short bullet points should be concise and focused on the main changes. Don't miss it.**

**Remember use simplified chinese**

The sent text will be the differences between files, where deleted lines are prefixed with a single minus sign and added lines are prefixed with a single plus sign.

Diff:
{diff}
