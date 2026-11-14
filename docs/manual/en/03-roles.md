\newpage

# Roles

[**cisys**](https://github.com/mnemosyne-systems/cisys) is designed around three roles, which are Keycloak realm roles. A signed-in user without one of them cannot use the application.

## Member

The **Member** role is the standard read-only role. Members can view public projects, and the build history and logs of the projects they can access.

## Lead

The **Lead** role is for people who run projects. Leads can do everything a member can, create projects, start manual builds on projects they are members of (a project owner adds them), and edit and pin builds of the projects they own.

## Admin

The **Admin** role manages the installation. Admins can see every project, start and pin builds without membership, use credential references for private Git repositories, manage the build images, change the branding, manage project membership, and change the settings such as build retention and the build queue.

## Access summary

| Action                              | Member | Lead | Admin |
| :---------------------------------- | :----- | :--- | :---- |
| View public projects                | yes    | yes  | yes   |
| View private projects they belong to | yes    | yes  | yes   |
| View other users' private projects  | no     | no   | yes   |
| Create a project                    | no     | yes  | yes   |
| View build history and logs         | yes    | yes  | yes   |
| Start a manual build                | no     | member projects | yes   |
| Pin a build                         | no     | projects they own | yes   |
| Add or remove project members       | projects they own | projects they own | yes   |
| Use a Git credential reference      | no     | no   | yes   |
| Manage build images                 | no     | no   | yes   |
| Change branding                     | no     | no   | yes   |
| Change settings (retention, queue)  | no     | no   | yes   |

"View" always means projects the user may access. The address of a project or build that does not exist or is not visible to the user opens the front page instead, so a private project is not revealed; the same applies to any unknown address.

Project membership scopes the projects a global role applies to; MEMBER membership does not grant powers on its own. Starting a build requires the Lead or Admin role **and** membership of the project — someone who is only a MEMBER of a project has no Lead powers. Every project has exactly one OWNER, its creator. The OWNER can add and remove members whatever their global role; admins can do the same on any project. Editing a project and pinning its builds need more: the Lead role **and** being the project's OWNER, or the Admin role. The OWNER role is never assignable through the membership API, and the project's OWNER cannot be removed.

The following chapters describe the application areas in more detail.
