<<package>>/*keyword*/ <<example>>/*namespace*/

<<class>>/*keyword*/ <<TryCatch>>/*class*/ {
  <<try>>/*keyword*/ {
    <<val>>/*keyword*/ <<x>>/*variable,readonly*/ = <<2>>/*number*/
    <<x>>/*variable,readonly*/ <<+>>/*method*/ <<2>>/*number*/
  } <<catch>>/*keyword*/ {
    <<case>>/*keyword*/ <<t>>/*variable,readonly*/: <<Throwable>>/*type*/ <<=>>>/*operator*/
      <<t>>/*variable,readonly*/.<<printStackTrace>>/*method*/()
  } <<finally>>/*keyword*/ {
    <<val>>/*keyword*/ <<text>>/*variable,readonly*/ = <<"">>/*string*/
    <<text>>/*variable,readonly*/ <<+>>/*method*/ <<"">>/*string*/
  }
}