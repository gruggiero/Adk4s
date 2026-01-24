$version: "2.0"
namespace org.adk4s.structured.test.projecttask

list TagList {
    member: String
}

structure ProjectTask {
    @required taskId: String
    @required title: String
    assignee: String
    status: String
    priority: String
    dueDate: String
    tags: TagList
}
