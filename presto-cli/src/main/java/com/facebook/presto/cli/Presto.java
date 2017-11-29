/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.cli;

import static io.airlift.airline.SingleCommand.singleCommand;

public final class Presto
{
    private Presto() {}

    //注解
    public static void main(String[] args)
            throws Exception
    {
        //利用airline在做cli命令行的解析
        Console console = singleCommand(Console.class).parse(args);

        //如果命令行中的可选项为：--help或者version则跳过
        if (console.helpOption.showHelpIfRequested() ||
                console.versionOption.showVersionIfRequested()) {
            return;
        }

        //启动执行cli
        console.run();
    }
}
