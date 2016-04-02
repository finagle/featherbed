# featherbed

[![Join the chat at https://gitter.im/finagle/featherbed](https://badges.gitter.im/finagle/featherbed.svg)](https://gitter.im/finagle/featherbed?utm_source=badge&utm_medium=badge&utm_campaign=pr-badge&utm_content=badge)
[![Build Status](https://img.shields.io/travis/finagle/featherbed/master.svg)](https://travis-ci.org/finagle/featherbed)
[![Maven Central](https://img.shields.io/maven-central/v/com.github.finagle/featherbed-core_2.11.svg)](https://maven-badges.herokuapp.com/maven-central/com.github.finagle/featherbed-core_2.11)

Featherbed aims to be a typesafe, functional REST client API over [Finagle](https://github.com/twitter/finagle).
It provides a friendlier approach to building REST client interfaces in Scala.  Currently, Featherbed
is in the early stages of development, and includes the following modules:

 1. `featherbed-core` - the functional client interface
 2. `featherbed-circe` - automatic JSON request encoding and response decoding using [circe](https://github.com/travisbrown/circe)

The following modules are planned:

 1. `featherbed-oauth` - OAuth authenticated requests

## Documentation
To get started with featherbed, check out the [Guide](https://finagle.github.io/featherbed/doc/).

## Dependencies

Featherbed aims to have a minimal set of dependencies.  Besides `finagle-http`, the core project is
dependent only on [shapeless](https://github.com/milessabin/shapeless) and [cats](https://github.com/typelevel/cats).

featherbed-circe depends additionall on [circe](https://github.com/travisbrown/circe)

## License

Featherbed is licensed under the [Apache License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0)
(the "License"); you may not use this software except in compliance with the License.

Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific
language governing permissions and limitations under the License.

### Dependency Licenses

As of the latest build of featherbed,

 * [Finagle](https://github.com/twitter/finagle) is licensed under the [Apache License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0)
 * [shapeless](https://github.com/milessabin/shapeless) is licensed under the [Apache License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0)
 * [cats](https://github.com/typelevel/cats) is licensed under the [MIT License](http://opensource.org/licenses/mit-license.php)
 * [circe](https://github.com/travisbrown/circe) is licensed under the [Apache License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0)

Featherbed is an independent project, and is neither governed nor endorsed by any of the above projects.  All uses of
the above projects by featherbed are within those allowed by each respective project's license.  Any software using
one of the above projects, even as a dependency of featherbed, must also abide by that project's license in addition to
featherbed's.
